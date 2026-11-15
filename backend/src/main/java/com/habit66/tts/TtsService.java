package com.habit66.tts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        return new Utterance(config.voiceFor(sentence.speaker()), config.rate(), sentence.enText());
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
                if (pass.failures() > 0 && !pass.aborted() && extraPasses < EXTRA_PASSES && !stopping) {
                    extraPasses++;
                    log.info("{} clips still missing, rescanning in {}s", pass.failures(), PASS_DELAY_MS / 1000);
                    Thread.sleep(PASS_DELAY_MS);
                    scanRequested.set(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** 없는 클립을 한 번 훑는다. */
    private Pass runPass() throws InterruptedException {
        List<Job> jobs = jobs();
        queued.set(jobs.size());
        failed.set(0);
        if (jobs.isEmpty()) {
            return new Pass(0, false);
        }
        log.info("Generating {} audio clips with edge-tts", jobs.size());
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
