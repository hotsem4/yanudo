package com.habit66.vocab;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.habit66.config.AppProperties;

/** {@code app.vocab.generator}로 생성기 하나를 고른다. */
@Configuration
public class VocabConfig {

    private static final Logger log = LoggerFactory.getLogger(VocabConfig.class);

    @Bean
    TermGenerator termGenerator(AppProperties props) {
        AppProperties.Vocab config = props.vocab();
        String name = config.generator() == null ? "" : config.generator().trim().toLowerCase(Locale.ROOT);
        switch (name) {
            case "claude-cli":
                return new ClaudeCliGenerator(config.claudeCommand(), config.claudeModel(), config.timeoutSeconds());
            case "anthropic-api":
                return new AnthropicApiGenerator(config.timeoutSeconds());
            case "none":
                return new NoopGenerator();
            default:
                log.warn("Unknown app.vocab.generator '{}', vocab generation is off", config.generator());
                return new NoopGenerator();
        }
    }
}
