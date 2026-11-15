package com.habit66.notify;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.habit66.config.AppProperties;

@Component
public class TelegramChannel implements ReminderChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private final AppProperties.Telegram config;
    private final RestClient http = Webhooks.client();

    public TelegramChannel(AppProperties props) {
        this.config = props.reminder().telegram();
    }

    @Override
    public Kind kind() {
        return Kind.WEBHOOK;
    }

    @Override
    public int send(ReminderMessage message) {
        if (!config.enabled() || config.botToken().isBlank() || config.chatId().isBlank()) {
            return 0;
        }
        try {
            http.post()
                    .uri("https://api.telegram.org/bot{token}/sendMessage", config.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", config.chatId(), "text", message.title() + "\n" + message.body()))
                    .retrieve()
                    .toBodilessEntity();
            return 1;
        } catch (RestClientResponseException e) {
            log.warn("Telegram reminder failed: HTTP {}", e.getStatusCode().value());
        } catch (Exception e) {
            // 예외 메시지에 봇 토큰이 든 주소가 들어갈 수 있어 종류만 남긴다.
            log.warn("Telegram reminder failed: {}", e.getClass().getSimpleName());
        }
        return 0;
    }
}
