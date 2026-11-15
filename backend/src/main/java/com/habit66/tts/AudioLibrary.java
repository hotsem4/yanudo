package com.habit66.tts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.habit66.common.ApiException;
import com.habit66.config.AppProperties;

import jakarta.annotation.PostConstruct;

/**
 * data/audio/*.mp3 의 유일한 쓰기 소유자. 디스크가 진실이고, 길이는 시작할 때 읽어 메모리에 색인한다.
 * 업로드·백엔드 생성 모두 {@link #accept}로만 들어온다.
 */
@Component
public class AudioLibrary {

    public record Accepted(String file, long durationMs, boolean created) {
    }

    private static final Logger log = LoggerFactory.getLogger(AudioLibrary.class);
    private static final Pattern NAME = Pattern.compile("[0-9a-f]{20}\\.mp3");
    private static final String URL_PREFIX = "/api/v1/audio/";
    private static final long ESTIMATE_MS_PER_WORD = 380;

    private final Path dir;
    private final Map<String, Long> index = new ConcurrentHashMap<>();

    public AudioLibrary(AppProperties props) {
        this.dir = props.audioPath();
    }

    @PostConstruct
    void init() {
        log.info("Indexed {} audio clips in {}", rescan(), dir);
    }

    public static boolean isClipName(String file) {
        return file != null && NAME.matcher(file).matches();
    }

    public OptionalLong find(String file) {
        Long duration = index.get(file);
        return duration == null ? OptionalLong.empty() : OptionalLong.of(duration);
    }

    /** 응답에 싣는 오디오 필드. 클립이 아직 없으면 URL 은 null 이고 길이는 단어 수 × 380ms 추정이다. */
    public TtsService.Clip clip(Utterance utterance) {
        String file = utterance.fileName();
        Long duration = index.get(file);
        if (duration != null) {
            return new TtsService.Clip(URL_PREFIX + file, duration, false);
        }
        int words = utterance.text().trim().split("\\s+").length;
        return new TtsService.Clip(null, words * ESTIMATE_MS_PER_WORD, true);
    }

    public int size() {
        return index.size();
    }

    /** 폴더를 다시 읽는다. 검증을 통과한 파일만 색인하고, 그 수를 돌려준다. */
    public synchronized int rescan() {
        Map<String, Long> found = new ConcurrentHashMap<>();
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.mp3")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!isClipName(name)) {
                        continue;
                    }
                    try {
                        OptionalLong duration = Mp3Frames.durationMs(Files.readAllBytes(file));
                        if (duration.isPresent()) {
                            found.put(name, duration.getAsLong());
                        } else {
                            log.warn("Ignoring invalid audio clip {}", name);
                        }
                    } catch (IOException e) {
                        log.warn("Cannot read audio clip {}: {}", name, e.toString());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan audio directory " + dir, e);
        }
        index.keySet().retainAll(found.keySet());
        index.putAll(found);
        return index.size();
    }

    /**
     * 검증 → 임시 파일 → 원자적 이동 → 길이 색인. 같은 이름의 유효한 클립이 이미 있으면 덮어쓰지 않고 성공한다.
     *
     * @throws ApiException 422 바이트가 받아들일 수 있는 MP3 가 아닐 때
     */
    public synchronized Accepted accept(String file, byte[] bytes) {
        if (!isClipName(file)) {
            throw new IllegalArgumentException("Not a clip name: " + file);
        }
        Path target = dir.resolve(file);
        Long existing = index.get(file);
        // 색인에는 있는데 파일이 사라졌으면(서버가 떠 있는 동안 폴더를 비운 경우) 다시 저장한다.
        if (existing != null && Files.isRegularFile(target)) {
            return new Accepted(file, existing, false);
        }
        Mp3Frames.Scan scan = Mp3Frames.scan(bytes);
        if (!scan.valid()) {
            throw ApiException.unprocessable("INVALID_AUDIO",
                    "MP3 로 받아들일 수 없습니다(프레임 " + scan.frames() + "개, 길이 " + scan.durationMs() + "ms).");
        }
        Path temp = dir.resolve("." + file + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store audio clip " + file, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 남은 임시 파일은 색인되지 않는다.
            }
        }
        index.put(file, scan.durationMs());
        return new Accepted(file, scan.durationMs(), true);
    }
}
