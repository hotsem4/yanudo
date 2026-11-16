package com.habit66.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 22시 리마인더의 선점·재시도 규칙. 실제 채널은 구독·설정이 없어 아무것도 보내지 않고, 가짜 채널이 성공·실패를 정한다.
 * 시계는 2026-11-15 10:00 에 고정(시작 보충이 돌지 않는 시각).
 */
@SpringBootTest(properties = {
        "app.data-dir=build/test-data/reminder",
        "app.tts.auto-generate=false",
        "app.reminder.enabled=true",
        "app.reminder.max-attempts=3",
        "app.reminder.telegram.enabled=false",
        "app.reminder.discord.enabled=false"
})
class ReminderServiceTest {

    private static final String TODAY = "2026-11-15";
    private static final FakeChannel CHANNEL = new FakeChannel();

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId seoul = ZoneId.of("Asia/Seoul");
            return Clock.fixed(LocalDateTime.of(2026, 11, 15, 10, 0).atZone(seoul).toInstant(), seoul);
        }

        @Bean
        ReminderChannel fakeChannel() {
            return CHANNEL;
        }
    }

    static final class FakeChannel implements ReminderChannel {

        final List<ReminderMessage> sent = new ArrayList<>();
        int result = 1;
        boolean explode;

        @Override
        public Kind kind() {
            return Kind.WEBHOOK;
        }

        @Override
        public int send(ReminderMessage message) {
            sent.add(message);
            if (explode) {
                throw new IllegalStateException("channel down");
            }
            return result;
        }
    }

    @Autowired
    private ReminderService reminders;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM reminder_log");
        jdbc.update("DELETE FROM day_achievement");
        jdbc.update("DELETE FROM sentence_view");
        jdbc.update("DELETE FROM push_subscription");
        jdbc.update("UPDATE app_setting SET value = ? WHERE key = 'start_date'", TODAY);
        CHANNEL.sent.clear();
        CHANNEL.result = 1;
        CHANNEL.explode = false;
    }

    @Test
    void scheduledRunsOutsideTheEveningWindowSendNothing() {
        // 고정 시계는 10:00. 절전에서 깨어나 밀린 22:00·재시도 작업이 아침에 돌아도 보내지 않고, 그날을 선점하지도 않는다.
        assertThat(reminders.inWindow()).isFalse();
        reminders.atDeadline();
        reminders.retry();

        assertThat(CHANNEL.sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminder_log", Integer.class)).isZero();
    }

    @Test
    void sendsOncePerDayWhenTodayIsNotAchieved() {
        reminders.remind("test");
        reminders.remind("test");

        assertThat(CHANNEL.sent).hasSize(1);
        ReminderMessage message = CHANNEL.sent.get(0);
        assertThat(message.title()).contains("Day 1");
        assertThat(message.body()).contains("10문장");
        assertThat(message.url()).isEqualTo("/?view=today");
        assertThat(message.tag()).isEqualTo("habit66-reminder");

        Map<String, Object> row = log();
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("push_sent")).intValue()).isZero();
        assertThat(((Number) row.get("webhooks_sent")).intValue()).isEqualTo(1);
    }

    @Test
    void doesNotSendWhenTodayIsAlreadyAchieved() {
        jdbc.update("INSERT INTO day_achievement (log_date, lesson_day, via, achieved_at) VALUES (?, 1, 'CARD', 1)",
                TODAY);

        reminders.remind("test");

        assertThat(CHANNEL.sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminder_log", Integer.class)).isZero();
    }

    @Test
    void totalFailureIsRetriedButOnlyUpToMaxAttempts() {
        CHANNEL.result = 0;

        for (int i = 0; i < 5; i++) {
            reminders.remind("test");
        }

        assertThat(CHANNEL.sent).hasSize(3);
        Map<String, Object> row = log();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
    }

    @Test
    void laterSuccessAfterAFailureMarksTheDaySent() {
        CHANNEL.result = 0;
        reminders.remind("deadline");
        assertThat(log().get("status")).isEqualTo("FAILED");

        CHANNEL.result = 1;
        reminders.remind("retry");
        reminders.remind("startup");

        assertThat(CHANNEL.sent).hasSize(2);
        Map<String, Object> row = log();
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void aChannelThatThrowsDoesNotBreakTheRun() {
        CHANNEL.explode = true;

        reminders.remind("test");

        assertThat(CHANNEL.sent).hasSize(1);
        assertThat(log().get("status")).isEqualTo("FAILED");
    }

    @Test
    void deliverReportsPushAndWebhookCountsSeparately() {
        ReminderService.Delivery delivery = reminders.deliver(new ReminderMessage("t", "b", "/", "tag"));

        assertThat(delivery.pushSent()).isZero();
        assertThat(delivery.webhooksSent()).isEqualTo(1);
    }

    private Map<String, Object> log() {
        return jdbc.queryForMap("SELECT status, attempts, push_sent, webhooks_sent FROM reminder_log WHERE log_date = ?",
                TODAY);
    }
}
