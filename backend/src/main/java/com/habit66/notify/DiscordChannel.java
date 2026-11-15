package com.habit66.notify;

import java.net.URI;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.habit66.config.AppProperties;

@Component
public class DiscordChannel implements ReminderChannel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);

    private final AppProperties.Discord config;
    private final RestClient http = Webhooks.client();

    public DiscordChannel(AppProperties props) {
        this.config = props.reminder().discord();
    }

    @Override
    public Kind kind() {
        return Kind.WEBHOOK;
    }

    @Override
    public int send(ReminderMessage message) {
        if (!config.enabled() || config.webhookUrl().isBlank()) {
            return 0;
        }
        try {
            http.post()
                    .uri(URI.create(config.webhookUrl().trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", "**" + message.title() + "**\n" + message.body()))
                    .retrieve()
                    .toBodilessEntity();
            return 1;
        } catch (RestClientResponseException e) {
            log.warn("Discord reminder failed: HTTP {}", e.getStatusCode().value());
        } catch (Exception e) {
            // 예외 메시지에 웹훅 주소(비밀)가 들어갈 수 있어 종류만 남긴다.
            log.warn("Discord reminder failed: {}", e.getClass().getSimpleName());
        }
        return 0;
    }
}
