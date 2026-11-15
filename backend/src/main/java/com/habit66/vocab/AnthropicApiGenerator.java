package com.habit66.vocab;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.habit66.vocab.TermStore.Example;

/**
 * {@code app.vocab.generator: anthropic-api}. 환경 변수 {@code ANTHROPIC_API_KEY}의 키로 Claude Haiku 5.5 를 부른다.
 * 출력 모양은 구조화 출력(레코드에서 만든 JSON 스키마)으로 고정한다.
 */
public class AnthropicApiGenerator implements TermGenerator {

    static final String MODEL = "claude-haiku-5-5";
    static final String KEY_HINT = "ANTHROPIC_API_KEY 환경 변수에 유효한 API 키를 넣고 서버를 다시 시작";

    record ExampleOut(String en, String ko) {
    }

    record EntryOut(String text, String meaning, String nuance, List<ExampleOut> examples) {
    }

    private final Duration timeout;
    private volatile AnthropicClient client;

    public AnthropicApiGenerator(int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Generated generate(Request request) throws GenerationException {
        StructuredMessageCreateParams<EntryOut> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4_000L)
                .outputConfig(EntryOut.class)
                .addUserMessage(TermPrompt.of(request))
                .build();
        StructuredMessage<EntryOut> response;
        try {
            response = client().messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new GenerationException("Anthropic API rejected the key: " + e.getMessage(), KEY_HINT, e);
        } catch (AnthropicServiceException e) {
            throw new GenerationException("Anthropic API error " + e.statusCode() + ": " + e.getMessage());
        } catch (AnthropicException e) {
            throw new GenerationException("Anthropic API call failed: " + e.getMessage());
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new GenerationException("Anthropic API declined the request (stop_reason=refusal)");
        }
        Optional<EntryOut> entry = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst();
        if (entry.isEmpty()) {
            throw new GenerationException("Anthropic API returned no structured output");
        }
        List<Example> examples = entry.get().examples() == null ? List.of()
                : entry.get().examples().stream().map(e -> new Example(e.en(), e.ko())).toList();
        return new Generated(entry.get().meaning(), entry.get().nuance(), examples).validated();
    }

    private AnthropicClient client() throws GenerationException {
        AnthropicClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    try {
                        current = AnthropicOkHttpClient.builder().fromEnv().timeout(timeout).build();
                    } catch (RuntimeException e) {
                        throw new GenerationException("Cannot create Anthropic client: " + e.getMessage(), KEY_HINT,
                                e);
                    }
                    client = current;
                }
            }
        }
        return current;
    }
}
