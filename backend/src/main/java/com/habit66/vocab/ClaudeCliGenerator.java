package com.habit66.vocab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class ClaudeCliGenerator implements TermGenerator, LoginProbe {

    static final String LOGIN_HINT = "터미널에서 claude 로그인 필요 (claude 를 실행해 /login)";
    static final String TOKEN_HINT = "장기 토큰이 만료됐거나 잘못됨 (claude setup-token 으로 새로 만들어 config/application.yml 에 넣기)";
    static final String COMMAND_HINT = "claude 명령을 찾을 수 없음 (app.vocab.claude-command 와 PATH 확인)";
    /** {@code claude setup-token}으로 만든 장기 토큰을 자식 프로세스에 넘기는 변수(Claude Code 가 읽는다). */
    static final String TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN";
    private static final String PROBE_PROMPT = "Reply with exactly: ok";

    static final String SCHEMA = """
            {"type":"object","properties":{"text":{"type":"string"},"meaning":{"type":"string"},\
            "nuance":{"type":"string"},"examples":{"type":"array","minItems":3,"maxItems":3,\
            "items":{"type":"object","properties":{"en":{"type":"string"},"ko":{"type":"string"}},\
            "required":["en","ko"],"additionalProperties":false}}},\
            "required":["text","meaning","nuance","examples"],"additionalProperties":false}""";

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final List<String> command;
    private final String model;
    private final int timeoutSeconds;
    private final String oauthToken;

    /** @param command 공백으로 나눈 실행 명령(기본 {@code claude}) */
    public ClaudeCliGenerator(String command, String model, int timeoutSeconds) {
        this(command, model, timeoutSeconds, "");
    }

    /** @param oauthToken {@code claude setup-token}으로 만든 장기 토큰. 비어 있으면 PC 에 로그인된 구독을 쓴다. */
    public ClaudeCliGenerator(String command, String model, int timeoutSeconds, String oauthToken) {
        this.command = splitCommand(command);
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.oauthToken = oauthToken == null ? "" : oauthToken.trim();
    }

    @Override
    public Generated generate(Request request) throws GenerationException {
        Run run = run(List.of("-p", "--setting-sources", "project", "--no-session-persistence",
                "--model", model, "--tools", "", "--output-format", "json",
                "--json-schema", SCHEMA, TermPrompt.of(request)));
        return read(run.stdout(), run.exitCode(), run.stderrTail());
    }

    /**
     * 로그인이 살아 있는지 본다. {@code claude auth status}는 토큰을 서버에 확인하지 않으므로(가짜 토큰도 로그인됨으로 나온다)
     * 아주 짧은 실제 호출로 판단하고, auth status 는 구독 로그인의 만료 예정 시각을 읽는 데만 쓴다.
     */
    @Override
    public LoginCheck checkLogin() {
        Long expiresAt = null;
        try {
            JsonNode status = JSON.readTree(run(List.of("auth", "status", "--json")).stdout());
            if (status != null && status.has("loggedIn") && !status.path("loggedIn").asBoolean(true)) {
                return LoginCheck.loginRequired(loginHint());
            }
            if (oauthToken.isEmpty() && status != null) {
                expiresAt = parseInstant(status.path("loginExpiresAt").asText(""));
            }
        } catch (GenerationException | JsonProcessingException e) {
            log.debug("claude auth status failed: {}", e.getMessage());
        }
        Run probe;
        try {
            probe = run(List.of("-p", "--setting-sources", "project", "--no-session-persistence",
                    "--model", model, "--tools", "", "--output-format", "json", PROBE_PROMPT));
        } catch (GenerationException e) {
            return LoginCheck.unknown(e.getMessage());
        }
        JsonNode root;
        try {
            root = JSON.readTree(probe.stdout());
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return LoginCheck.unknown("claude printed no JSON result (exit code " + probe.exitCode() + "): "
                    + probe.stderrTail());
        }
        if (root.path("is_error").asBoolean(false)) {
            String message = root.path("result").asText("unknown error");
            return isLoginError(root, message) ? LoginCheck.loginRequired(loginHint()) : LoginCheck.unknown(message);
        }
        return LoginCheck.ok(expiresAt);
    }

    /** 생성과 점검이 같은 기준으로 로그인 문제를 가린다. 401 이 오면 문구와 상관없이 로그인 문제다. */
    static boolean isLoginError(JsonNode root, String message) {
        return root.path("api_error_status").asInt(0) == 401
                || "authentication_error".equals(root.path("api_error_type").asText(""))
                || looksLikeLoginProblem(message);
    }

    private String loginHint() {
        return oauthToken.isEmpty() ? LOGIN_HINT : TOKEN_HINT;
    }

    static Long parseInstant(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    record Run(String stdout, int exitCode, String stderrTail) {
    }

    /** 빈 임시 폴더에서 claude 를 한 번 실행한다. 시간을 넘기면 프로세스 트리를 끝낸다. */
    private Run run(List<String> cliArgs) throws GenerationException {
        Path workDir = null;
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("habit66-vocab-");
            stdout = Files.createTempFile("habit66-vocab-", ".out");
            stderr = Files.createTempFile("habit66-vocab-", ".err");

            List<String> args = new ArrayList<>(command);
            args.addAll(cliArgs);
            ProcessBuilder builder = new ProcessBuilder(WINDOWS ? windowsArgs(args) : args);
            builder.directory(workDir.toFile());
            stripInheritedSession(builder.environment());
            if (!oauthToken.isEmpty()) {
                builder.environment().put(TOKEN_ENV, oauthToken);
            }
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new GenerationException("cannot run '" + String.join(" ", command) + "': " + e.getMessage(),
                        COMMAND_HINT, e);
            }
            // 입력을 닫지 않으면 CLI 가 표준 입력을 기다릴 수 있다.
            process.getOutputStream().close();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new GenerationException("claude timed out after " + timeoutSeconds + "s");
            }
            return new Run(Files.readString(stdout, StandardCharsets.UTF_8), process.exitValue(), lastLine(stderr));
        } catch (IOException e) {
            // 임시 파일·출력 읽기 실패는 이번 한 번의 문제다. 사용자 조치(로그인 등)가 필요한 실패로 보지 않는다.
            throw new GenerationException("claude run failed: " + e.getMessage());
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

    /** 설정값 전체가 실제 파일이면(예: C:\Program Files\...\claude.exe) 공백으로 나누지 않는다. */
    static List<String> splitCommand(String command) {
        String trimmed = command.trim();
        String unquoted = trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
        try {
            if (Files.isRegularFile(Path.of(unquoted))) {
                return List.of(unquoted);
            }
        } catch (RuntimeException e) {
            // 경로로 읽을 수 없는 값이면 명령과 인자로 본다.
        }
        return Arrays.asList(trimmed.split("\\s+"));
    }

    /** 표준 출력의 결과 JSON 을 읽는다. 패키지 안 테스트가 직접 부른다. */
    Generated read(String output, int exitCode, Path stderr) throws GenerationException {
        return read(output, exitCode, lastLine(stderr));
    }

    private Generated read(String output, int exitCode, String stderrTail) throws GenerationException {
        JsonNode root;
        try {
            root = JSON.readTree(output);
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new GenerationException("claude printed no JSON result (exit code " + exitCode + "): "
                    + stderrTail);
        }
        if (root.path("is_error").asBoolean(false)) {
            String message = root.path("result").asText("unknown error");
            throw new GenerationException("claude reported an error (exit code " + exitCode + "): " + message,
                    isLoginError(root, message) ? loginHint() : null);
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

    /**
     * CLI 결과에는 오류 코드가 없고 문장뿐이라 문구로 가려낸다(관찰된 예: "Failed to authenticate: OAuth session expired …").
     * 안전장치가 요청을 거절한 응답(오탐 포함)은 로그인 문제가 아니다. 그 표현만 FAILED 로 두고 큐는 계속 돈다.
     */
    static boolean looksLikeLoginProblem(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (Stream.of("usage policy", "unable to respond", "violate").anyMatch(lower::contains)) {
            return false;
        }
        return Stream.of("authenticate", "oauth", "/login", "log in", "logged in", "credential", "api key")
                .anyMatch(lower::contains);
    }

    /**
     * 서버가 Claude Code 안(데스크톱 앱, 터미널 세션)에서 시작되면 그 세션의 CLAUDE*·ANTHROPIC* 변수가 상속되고,
     * 자식 claude 가 그 값을 따라 구독 로그인 대신 다른 인증을 시도해 실패한다(2026-11-15 실측). 모두 지우고 실행한다.
     */
    static void stripInheritedSession(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.startsWith("CLAUDE") || upper.startsWith("ANTHROPIC");
        });
    }

    /**
     * Windows 의 ProcessBuilder 는 인자 속 큰따옴표를 이스케이프하지 않아 자식에서 따옴표가 사라진다(JSON 스키마가 깨진다).
     * 표준 C 런타임 규칙대로 직접 감싸 넘긴다. 이미 따옴표로 감싼 인자는 ProcessBuilder 가 그대로 둔다.
     */
    static List<String> windowsArgs(List<String> args) {
        List<String> quoted = new ArrayList<>(args.size());
        quoted.add(args.get(0));
        for (String arg : args.subList(1, args.size())) {
            quoted.add(quoteForWindows(arg));
        }
        return quoted;
    }

    static String quoteForWindows(String arg) {
        StringBuilder out = new StringBuilder("\"");
        int slashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                slashes++;
                continue;
            }
            if (c == '"') {
                out.append("\\".repeat(slashes * 2 + 1)).append('"');
            } else {
                out.append("\\".repeat(slashes)).append(c);
            }
            slashes = 0;
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString();
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
