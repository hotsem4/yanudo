package com.habit66.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
