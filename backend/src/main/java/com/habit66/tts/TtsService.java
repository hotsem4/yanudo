package com.habit66.tts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.habit66.config.AppProperties;
import com.habit66.content.Catalog;
import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;
import com.habit66.content.CatalogChanged;
import com.habit66.content.CatalogService;

import jakarta.annotation.PreDestroy;

/** 카탈로그 ↔ 클립 대응, 없는 클립 목록, 백엔드 edge-tts 생성(단일 스레드). */
@Service
public class TtsService {

    public record Job(String file, String voice, String rate, String text, List<Long> sentenceIds) {
    }

    /**
     * @param done   서버가 뜬 뒤 백엔드가 만든 클립 수
     * @param failed 가장 최근 스캔에서 끝내 만들지 못한 클립 수
     */
    public record Status(boolean running, int queued, int done, int failed, String lastError, int clipsTotal,
            int clipsMissing) {
    }

    /** 세션 응답의 오디오 필드. 클립이 없으면 URL 은 null 이고 길이는 추정값이다. */
    public record Clip(String audioUrl, long durationMs, boolean durationEstimated) {
    }

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    /** edge-tts 서비스는 가끔 "No audio was received" 로 실패한다. 클립마다 간격을 늘리며 다시 시도한다. */
    private static final int ATTEMPTS_PER_CLIP = 3;
    private static final long BACKOFF_MS = 2_000;
    /** 연달아 이만큼의 클립이 끝내 실패하면 네트워크가 끊긴 것으로 보고 이번 실행을 멈춘다. */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 실패가 남으면 잠시 쉬고 스스로 다시 스캔한다. 그래도 남은 것은 다음 generate·재시작 때 다시 잡힌다. */
    private static final int EXTRA_PASSES = 2;
    private static final long PASS_DELAY_MS = 15_000;
    /** 연속 실패로 중단한 뒤(부팅 직후 네트워크가 아직 없을 때 등) 다시 훑기까지 기다리는 시간. */
    private static final long ABORT_DELAY_MS = 120_000;

    private final AppProperties.Tts config;
    private final CatalogService catalog;
    private final AudioLibrary library;
    private final ObjectProvider<ClipSource> sources;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean scanRequested = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile String lastError;
    private volatile boolean stopping;

    public TtsService(AppProperties props, CatalogService catalog, AudioLibrary library,
            ObjectProvider<ClipSource> sources) {
        this.config = props.tts();
        this.catalog = catalog;
        this.library = library;
        this.sources = sources;
    }

    /** 백엔드가 스스로 음성을 만드는 설정인가(꺼져 있으면 스크립트로 채운다). */
    public boolean autoGenerate() {
        return config.autoGenerate();
    }

    public Utterance utteranceOf(SentenceView sentence) {
        return new Utterance(config.voiceFor(sentence.speaker()), config.rate(), sentence.enText(), config.variant());
    }

    /** 지금 쓰는 합성 엔진("azure" | "edge"). 일괄 생성 스크립트는 azure 면 직접 만들지 않는다(낮은 품질로 덮어쓰지 않게). */
    public String engine() {
        return config.useAzure() ? "azure" : "edge";
    }

    public Clip clipOf(SentenceView sentence) {
        return library.clip(utteranceOf(sentence));
    }

    /** 현재 카탈로그가 이 파일 이름을 쓰는가(모르는 이름의 업로드는 받지 않는다). */
    public boolean knows(String file) {
        return allClips().containsKey(file);
    }

    /** 아직 없는 클립. 같은 화자·문장은 한 작업으로 묶인다. */
    public List<Job> jobs() {
        return allClips().values().stream().filter(job -> library.find(job.file()).isEmpty()).toList();
    }

    /** 없는 클립 생성을 시작한다. 이미 돌고 있으면 끝난 뒤 한 번 더 스캔하도록 표시만 한다. */
    public boolean generate() {
        scanRequested.set(true);
        if (!stopping && running.compareAndSet(false, true)) {
            worker.execute(this::drain);
        }
        return true;
    }

    public Status status() {
        Map<String, Job> all = allClips();
        int missing = (int) all.keySet().stream().filter(file -> library.find(file).isEmpty()).count();
        return new Status(running.get(), queued.get(), done.get(), failed.get(), lastError, all.size(), missing);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (config.autoGenerate()) {
            generate();
        }
    }

    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        if (config.autoGenerate()) {
            generate();
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        // 워커를 깨우면 synthesize 의 finally 가 실행 중인 프로세스와 임시 파일을 정리한다.
        worker.shutdownNow();
    }

    private Map<String, Job> allClips() {
        Catalog cat = catalog.current();
        Map<String, Job> clips = new LinkedHashMap<>();
        for (LessonView lesson : cat.lessons()) {
            for (SentenceView sentence : lesson.sentences()) {
                Utterance u = utteranceOf(sentence);
                clips.computeIfAbsent(u.fileName(),
                        file -> new Job(file, u.voice(), u.rate(), u.text(), new ArrayList<>()))
                        .sentenceIds().add(sentence.id());
            }
        }
        // 레슨 문장이 아닌 발화(단어장 예문)는 sentence_ids 가 비어 있다.
        sources.orderedStream().forEach(source -> {
            for (Utterance u : source.utterances()) {
                clips.computeIfAbsent(u.fileName(),
                        file -> new Job(file, u.voice(), u.rate(), u.text(), new ArrayList<>()));
            }
        });
        return clips;
    }

    private void drain() {
        try {
            int extraPasses = 0;
            while (!stopping && scanRequested.getAndSet(false)) {
                Pass pass = runPass();
                if (pass.failures() > 0 && extraPasses < EXTRA_PASSES && !stopping) {
                    extraPasses++;
                    long delay = pass.aborted() ? ABORT_DELAY_MS : PASS_DELAY_MS;
                    log.info("{} clips still missing, rescanning in {}s", pass.failures(), delay / 1000);
                    waitOrWake(delay);
                    scanRequested.set(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // 카탈로그 조회 실패 등. 조용히 멈추면 10분 재시도도 돌지 않으므로 오류로 남긴다.
            lastError = "audio generation stopped: " + describe(e);
            log.error("Audio generation stopped unexpectedly", e);
        } finally {
            queued.set(0);
            running.set(false);
            // running 을 내리는 사이에 들어온 요청을 놓치지 않는다.
            if (!stopping && scanRequested.get()) {
                generate();
            }
        }
    }

    private record Pass(int failures, boolean aborted) {
    }

    /** 기다리되, 그사이 새 생성 요청(임포트·단어 담기·수동 호출)이 오면 바로 다시 훑는다. */
    private void waitOrWake(long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!stopping && !scanRequested.get() && System.currentTimeMillis() < until) {
            Thread.sleep(500);
        }
    }

    /**
     * 마지막 실행에서 클립을 만들지 못했으면(부팅 직후 네트워크가 몇 분 넘게 없던 경우 등) 10분마다 다시 시도한다.
     * 모두 만들어졌으면(lastError 없음) 아무것도 하지 않는다.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    public void retryMissing() {
        if (lastError != null && !running.get() && autoGenerate()) {
            log.info("Retrying missing audio clips after earlier failures");
            generate();
        }
    }

    /** 없는 클립을 한 번 훑는다. */
    private Pass runPass() throws InterruptedException {
        List<Job> jobs = jobs();
        queued.set(jobs.size());
        failed.set(0);
        if (jobs.isEmpty()) {
            lastError = null; // 다른 경로(스크립트 업로드 등)로 다 채워졌다. 10분 재시도를 멈춘다.
            return new Pass(0, false);
        }
        log.info("Generating {} audio clips with {}", jobs.size(), engine());
        int failures = 0;
        int consecutive = 0;
        for (Job job : jobs) {
            if (stopping) {
                break;
            }
            if (library.find(job.file()).isEmpty()) {
                try {
                    synthesizeWithRetry(job);
                    done.incrementAndGet();
                    consecutive = 0;
                } catch (IOException | RuntimeException e) {
                    failures++;
                    failed.incrementAndGet();
                    lastError = job.file() + ": " + describe(e);
                    log.warn("edge-tts failed for {}: {}", job.file(), describe(e));
                    if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("Stopping audio generation after {} consecutive failures", consecutive);
                        queued.set(0);
                        return new Pass(failures, true);
                    }
                }
            }
            queued.decrementAndGet();
        }
        queued.set(0);
        if (failures == 0) {
            lastError = null;
        }
        return new Pass(failures, false);
    }

    private void synthesizeWithRetry(Job job) throws IOException, InterruptedException {
        for (int attempt = 1;; attempt++) {
            try {
                synthesize(job);
                return;
            } catch (IOException | RuntimeException e) {
                if (attempt >= ATTEMPTS_PER_CLIP || stopping) {
                    throw e;
                }
                long wait = BACKOFF_MS << (attempt - 1);
                log.info("edge-tts attempt {}/{} failed for {} ({}), retrying in {}ms", attempt, ATTEMPTS_PER_CLIP,
                        job.file(), describe(e), wait);
                Thread.sleep(wait);
            }
        }
    }

    private void synthesize(Job job) throws IOException, InterruptedException {
        if (config.useAzure()) {
            synthesizeAzure(job);
            return;
        }
        Path text = null;
        Path media = null;
        Path output = null;
        Process process = null;
        try {
            text = Files.createTempFile("habit66-tts-", ".txt");
            media = Files.createTempFile("habit66-tts-", ".mp3");
            output = Files.createTempFile("habit66-tts-", ".log");
            // 문장은 파일로 넘겨 Windows 인자 따옴표 문제를 피한다.
            Files.writeString(text, job.text(), StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(Arrays.asList(config.command().trim().split("\\s+")));
            command.addAll(List.of("--voice", job.voice(), "--rate=" + job.rate(), "--file", text.toString(),
                    "--write-media", media.toString()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            process = builder.start();
            if (!process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("timed out after " + config.timeoutSeconds() + "s");
            }
            if (process.exitValue() != 0) {
                throw new IOException("exit code " + process.exitValue() + ": " + lastLine(output));
            }
            byte[] bytes = Files.readAllBytes(media);
            if (bytes.length == 0) {
                throw new IOException("empty output: " + lastLine(output));
            }
            library.accept(job.file(), bytes);
        } finally {
            // 시간 초과·종료 인터럽트로 빠져나올 때 프로세스가 남지 않게 하고, 끝난 뒤에 파일을 지운다(Windows 잠금).
            if (process != null && process.isAlive()) {
                // python 이 런처(py, venv 리다이렉터)면 실제 인터프리터는 자식 프로세스다.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delete(text);
            delete(media);
            delete(output);
        }
    }

    // ── Azure Speech(REST) ──
    /** 무료 등급(F0)은 1분에 20번까지라 요청 사이를 띄운다. */
    private static final long AZURE_SPACING_MS = 3_100;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private long lastAzureAt = 0;

    private void synthesizeAzure(Job job) throws IOException, InterruptedException {
        AppProperties.Azure azure = config.azure();
        long wait = lastAzureAt + AZURE_SPACING_MS - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        String ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">"
                + "<voice name=\"" + xml(job.voice()) + "\"><prosody rate=\"" + xml(job.rate()) + "\">" + xml(job.text())
                + "</prosody></voice></speak>";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + azure.region().trim() + ".tts.speech.microsoft.com/cognitiveservices/v1"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Ocp-Apim-Subscription-Key", azure.key().trim())
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", azure.format())
                .header("User-Agent", "habit66")
                .POST(HttpRequest.BodyPublishers.ofString(ssml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } finally {
            lastAzureAt = System.currentTimeMillis();
        }
        int status = response.statusCode();
        if (status == 429) {
            // 속도 제한: 알려 준 만큼(없으면 10초) 쉬고 같은 클립을 다시 시도한다(재시도 횟수에 넣지 않는다).
            long retry = response.headers().firstValueAsLong("Retry-After").orElse(10);
            log.info("Azure Speech rate limit; waiting {}s", retry);
            Thread.sleep(Math.min(60, Math.max(1, retry)) * 1000);
            synthesizeAzure(job);
            return;
        }
        if (status == 401 || status == 403) {
            throw new IOException("Azure Speech rejected the key or region (HTTP " + status + ")");
        }
        if (status != 200 || response.body().length == 0) {
            throw new IOException("Azure Speech answered HTTP " + status);
        }
        library.accept(job.file(), response.body());
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Cannot delete temp file {}: {}", file, e.toString());
        }
    }

    /** 프로세스 출력의 마지막 줄(파이썬 예외 메시지). 경로가 든 트레이스백은 싣지 않는다. */
    private static String lastLine(Path output) {
        try {
            List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
            if (lines.isEmpty()) {
                return "(no output)";
            }
            String last = lines.get(lines.size() - 1);
            return last.length() <= 240 ? last : last.substring(0, 240);
        } catch (IOException | RuntimeException e) {
            return "(no output)";
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
