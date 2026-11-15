package com.habit66.vocab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.vocab.TermStore.Example;

/**
 * 로그인된 Claude Code 구독으로 {@code claude -p}를 헤드리스 실행한다(SPEC F-08, docs/architecture.md).
 *
 * <pre>
 * claude -p --setting-sources project --no-session-persistence --model &lt;model&gt; --tools ""
 *        --output-format json --json-schema &lt;스키마&gt; "&lt;프롬프트&gt;"
 * </pre>
 *
 * <ul>
 * <li>빈 임시 폴더에서 실행하고 {@code --setting-sources project}를 주어 사용자 전역 설정·훅이 끼어들지 않게 한다.</li>
 * <li>{@code --bare}는 구독 로그인(OAuth)을 막으므로 쓰지 않는다.</li>
 * <li>{@code --tools}는 값을 여러 개 받는 옵션이라, 빈 값 바로 뒤에 다른 옵션이 와야 프롬프트가 도구 이름으로 읽히지 않는다.
 * 인자 순서를 바꾸지 말 것.</li>
 * <li>실패해도 종료 코드와 함께 표준 출력에 JSON 이 온다(로그인 만료: 종료 코드 1, {@code is_error: true}이면서
 * {@code subtype: "success"}). 그래서 종료 코드나 subtype 이 아니라 {@code is_error}로 판단한다.</li>
 * </ul>
 */
public class ClaudeCliGenerator implements TermGenerator {

    static final String LOGIN_HINT = "터미널에서 claude 로그인 필요 (claude 를 실행해 /login)";

    static final String SCHEMA = """
            {"type":"object","properties":{"text":{"type":"string"},"meaning":{"type":"string"},\
            "nuance":{"type":"string"},"examples":{"type":"array","minItems":3,"maxItems":3,\
            "items":{"type":"object","properties":{"en":{"type":"string"},"ko":{"type":"string"}},\
            "required":["en","ko"],"additionalProperties":false}}},\
            "required":["text","meaning","nuance","examples"],"additionalProperties":false}""";

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> command;
    private final String model;
    private final int timeoutSeconds;

    /** @param command 공백으로 나눈 실행 명령(기본 {@code claude}) */
    public ClaudeCliGenerator(String command, String model, int timeoutSeconds) {
        this.command = Arrays.asList(command.trim().split("\\s+"));
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Generated generate(Request request) throws GenerationException {
        Path workDir = null;
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("habit66-vocab-");
            stdout = Files.createTempFile("habit66-vocab-", ".out");
            stderr = Files.createTempFile("habit66-vocab-", ".err");

            List<String> args = new ArrayList<>(command);
            args.addAll(List.of("-p", "--setting-sources", "project", "--no-session-persistence",
                    "--model", model, "--tools", "", "--output-format", "json",
                    "--json-schema", SCHEMA, TermPrompt.of(request)));
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(workDir.toFile());
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            process = builder.start();
            // 입력을 닫지 않으면 CLI 가 표준 입력을 기다릴 수 있다.
            process.getOutputStream().close();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new GenerationException("claude timed out after " + timeoutSeconds + "s");
            }
            return read(Files.readString(stdout, StandardCharsets.UTF_8), process.exitValue(), stderr);
        } catch (IOException e) {
            throw new GenerationException("cannot run '" + String.join(" ", command) + "': " + e.getMessage(),
                    "claude 명령을 찾을 수 없음 (app.vocab.claude-command 와 PATH 확인)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenerationException("interrupted while waiting for claude");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            delete(stdout);
            delete(stderr);
            deleteTree(workDir);
        }
    }

    /** 표준 출력의 결과 JSON 을 읽는다. 패키지 안 테스트가 직접 부른다. */
    Generated read(String output, int exitCode, Path stderr) throws GenerationException {
        JsonNode root;
        try {
            root = JSON.readTree(output);
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new GenerationException("claude printed no JSON result (exit code " + exitCode + "): "
                    + lastLine(stderr));
        }
        if (root.path("is_error").asBoolean(false)) {
            String message = root.path("result").asText("unknown error");
            throw new GenerationException("claude reported an error (exit code " + exitCode + "): " + message,
                    looksLikeLoginProblem(message) ? LOGIN_HINT : null);
        }
        JsonNode entry = root.path("structured_output");
        if (!entry.isObject()) {
            // 구조화 출력 필드가 없으면 본문(result)이 JSON 문자열일 수 있다.
            try {
                entry = JSON.readTree(root.path("result").asText(""));
            } catch (JsonProcessingException e) {
                entry = null;
            }
        }
        if (entry == null || !entry.isObject()) {
            throw new GenerationException("claude result has no structured_output and result is not JSON");
        }
        List<Example> examples = new ArrayList<>();
        for (JsonNode example : entry.path("examples")) {
            examples.add(new Example(example.path("en").asText(""), example.path("ko").asText("")));
        }
        return new Generated(entry.path("meaning").asText(""), entry.path("nuance").asText(""), examples)
                .validated();
    }

    /** CLI 결과에는 오류 코드가 없고 문장뿐이라 문구로 가려낸다(관찰된 예: "Failed to authenticate: OAuth session expired …"). */
    private static boolean looksLikeLoginProblem(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return Stream.of("authenticate", "oauth", "/login", "log in", "logged in", "credential", "api key")
                .anyMatch(lower::contains);
    }

    private static String lastLine(Path stderr) {
        try {
            List<String> lines = Files.readAllLines(stderr, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
            if (lines.isEmpty()) {
                return "(no error output)";
            }
            String last = lines.get(lines.size() - 1);
            return last.length() <= 240 ? last : last.substring(0, 240);
        } catch (IOException | RuntimeException e) {
            return "(no error output)";
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

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(ClaudeCliGenerator::delete);
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot delete temp directory {}: {}", dir, e.toString());
        }
    }
}
