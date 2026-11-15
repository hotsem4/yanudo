package com.habit66.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.habit66.vocab.TermGenerator.Generated;
import com.habit66.vocab.TermGenerator.Request;

class ClaudeCliGeneratorTest {

    private final ClaudeCliGenerator generator = new ClaudeCliGenerator("claude", "haiku", 120);

    @TempDir
    Path temp;

    @Test
    void readsStructuredOutput() throws Exception {
        String output = """
                {"type":"result","subtype":"success","is_error":false,"result":"",
                 "structured_output":{"text":"grab","meaning":" 가볍게 먹다 ","nuance":"친구 사이 구어체.",
                  "examples":[{"en":"Let's grab lunch.","ko":"점심 먹자."},{"en":"I'll grab a coffee.","ko":"커피 사 올게."},
                              {"en":"Grab a seat.","ko":"앉아."}]}}""";
        Generated generated = generator.read(output, 0, stderr());
        assertThat(generated.meaning()).isEqualTo("가볍게 먹다");
        assertThat(generated.examples()).hasSize(3);
        assertThat(generated.examples().get(2).en()).isEqualTo("Grab a seat.");
    }

    @Test
    void expiredLoginNeedsUserAction() throws Exception {
        String output = """
                {"type":"result","subtype":"success","is_error":true,
                 "result":"Failed to authenticate: OAuth session expired. Please run /login"}""";
        assertThatThrownBy(() -> generator.read(output, 1, stderr()))
                .isInstanceOfSatisfying(GenerationException.class, e -> {
                    assertThat(e.needsUserAction()).isTrue();
                    assertThat(e.hint()).isEqualTo(ClaudeCliGenerator.LOGIN_HINT);
                });
    }

    @Test
    void safetyRefusalFailsOnlyThatTerm() throws Exception {
        String output = """
                {"type":"result","subtype":"success","is_error":true,
                 "result":"API Error: Claude Code is unable to respond to this request, which appears to violate our Usage Policy. Please double press esc to edit your last message or log in again."}""";
        assertThatThrownBy(() -> generator.read(output, 1, stderr()))
                .isInstanceOfSatisfying(GenerationException.class, e -> assertThat(e.needsUserAction()).isFalse());
    }

    @Test
    void refusalTextWithoutStructuredOutputFails() throws Exception {
        String output = """
                {"type":"result","subtype":"success","is_error":false,"result":"I can't help with that."}""";
        assertThatThrownBy(() -> generator.read(output, 0, stderr()))
                .isInstanceOfSatisfying(GenerationException.class, e -> assertThat(e.needsUserAction()).isFalse());
    }

    @Test
    void incompleteEntryFails() {
        String output = """
                {"is_error":false,"structured_output":{"text":"grab","meaning":"m","nuance":"n",
                 "examples":[{"en":"a","ko":"b"}]}}""";
        assertThatThrownBy(() -> generator.read(output, 0, stderr())).isInstanceOf(GenerationException.class);
    }

    @Test
    void notJsonFails() {
        assertThatThrownBy(() -> generator.read("Error: unknown option", 2, stderr()))
                .isInstanceOf(GenerationException.class);
    }

    @Test
    void stripsInheritedClaudeAndAnthropicVariables() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "x", "CLAUDECODE", "1",
                "CLAUDE_CODE_ENTRYPOINT", "desktop", "ANTHROPIC_BASE_URL", "http://proxy", "anthropic_api_key", "k",
                "HOME", "h"));
        ClaudeCliGenerator.stripInheritedSession(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME");
    }

    @Test
    void quotesArgumentsByWindowsCRules() {
        assertThat(ClaudeCliGenerator.quoteForWindows("")).isEqualTo("\"\"");
        assertThat(ClaudeCliGenerator.quoteForWindows("{\"a\":1}")).isEqualTo("\"{\\\"a\\\":1}\"");
        assertThat(ClaudeCliGenerator.quoteForWindows("C:\\dir\\")).isEqualTo("\"C:\\dir\\\\\"");
        assertThat(ClaudeCliGenerator.quoteForWindows("a\\\"b")).isEqualTo("\"a\\\\\\\"b\"");
        assertThat(ClaudeCliGenerator.windowsArgs(List.of("claude", "-p", ""))).containsExactly("claude",
                "\"-p\"", "\"\"");
    }

    @Test
    void commandPathWithSpacesStaysWhole() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("Program Files"));
        Path exe = Files.writeString(dir.resolve("claude.exe"), "");
        assertThat(ClaudeCliGenerator.splitCommand(exe.toString())).containsExactly(exe.toString());
        assertThat(ClaudeCliGenerator.splitCommand("\"" + exe + "\"")).containsExactly(exe.toString());
        assertThat(ClaudeCliGenerator.splitCommand("npx claude")).containsExactly("npx", "claude");
    }

    @Test
    void authenticationErrorIsALoginProblemEvenWithoutKnownWords() throws Exception {
        String output = """
                {"type":"result","subtype":"success","is_error":true,"api_error_status":401,
                 "api_error_type":"authentication_error","result":"API Error: 401 something new"}""";
        assertThatThrownBy(() -> generator.read(output, 1, stderr()))
                .isInstanceOfSatisfying(GenerationException.class,
                        e -> assertThat(e.hint()).isEqualTo(ClaudeCliGenerator.LOGIN_HINT));
    }

    // ── 가짜 claude(파이썬 스크립트)로 실제 프로세스 실행 경로를 확인한다 ──

    /** 토큰 "good" 이면 성공, 다른 토큰이면 401(관찰한 실제 응답 모양), 토큰이 없으면 구독 로그인(만료 시각 있음). */
    private static final String FAKE_CLAUDE = """
            import json, os, sys
            args = sys.argv[1:]
            token = os.environ.get("CLAUDE_CODE_OAUTH_TOKEN", "")
            leaked = [k for k in os.environ if k.upper().startswith(("CLAUDE", "ANTHROPIC")) and k != "CLAUDE_CODE_OAUTH_TOKEN"]
            if args[:2] == ["auth", "status"]:
                print(json.dumps({"loggedIn": True, "loginExpiresAt": None if token else "2030-01-02T03:04:05.000Z"}))
                sys.exit(0)
            if leaked:
                print(json.dumps({"type": "result", "is_error": True, "result": "leaked " + ",".join(leaked)}))
                sys.exit(1)
            if token and token != "good":
                print(json.dumps({"type": "result", "subtype": "success", "is_error": True, "api_error_status": 401,
                                  "api_error_type": "authentication_error",
                                  "result": "Failed to authenticate. API Error: 401 OAuth access token is invalid."}))
                sys.exit(1)
            print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": "ok",
                              "structured_output": {"text": "grab", "meaning": "잡다", "nuance": "구어체.",
                                                    "examples": [{"en": "Grab it.", "ko": "잡아."}] * 3}}))
            """;

    private ClaudeCliGenerator fake(String token) throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "python not on PATH");
        Path script = Files.writeString(temp.resolve("fake_claude.py"), FAKE_CLAUDE);
        return new ClaudeCliGenerator("python " + script, "haiku", 60, token);
    }

    @Test
    void passesTheLongLivedTokenToClaude() throws Exception {
        LoginProbe.LoginCheck check = fake("good").checkLogin();
        assertThat(check.result()).isEqualTo(LoginProbe.Result.OK);
        assertThat(check.expiresAt()).isNull(); // 장기 토큰은 만료 시각을 알 수 없다
        assertThat(fake("good").generate(new Request("grab", null)).meaning()).isEqualTo("잡다");
    }

    @Test
    void invalidTokenAsksForANewToken() throws Exception {
        LoginProbe.LoginCheck check = fake("expired").checkLogin();
        assertThat(check.result()).isEqualTo(LoginProbe.Result.LOGIN_REQUIRED);
        assertThat(check.hint()).isEqualTo(ClaudeCliGenerator.TOKEN_HINT);
        assertThatThrownBy(() -> fake("expired").generate(new Request("grab", null)))
                .isInstanceOfSatisfying(GenerationException.class,
                        e -> assertThat(e.hint()).isEqualTo(ClaudeCliGenerator.TOKEN_HINT));
    }

    @Test
    void subscriptionLoginReportsItsExpiry() throws Exception {
        LoginProbe.LoginCheck check = fake("").checkLogin();
        assertThat(check.result()).isEqualTo(LoginProbe.Result.OK);
        assertThat(check.expiresAt()).isEqualTo(Instant.parse("2030-01-02T03:04:05Z").toEpochMilli());
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 실제 구독 로그인으로 한 번 만들어 본다. HABIT66_LIVE_CLAUDE=1 일 때만 돈다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "HABIT66_LIVE_CLAUDE", matches = "1")
    void generatesWithTheRealCli() throws Exception {
        Generated generated = generator.generate(
                new Request("grab", "How about we \"grab\" dinner together? C:\\temp\\"));
        assertThat(generated.meaning()).isNotBlank();
        assertThat(generated.examples()).hasSize(3);
        System.out.println("LIVE: " + generated);
    }

    private Path stderr() throws Exception {
        return Files.writeString(temp.resolve("err.txt"), "");
    }
}
