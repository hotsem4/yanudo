package com.habit66.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.config.AppProperties;
import com.habit66.notify.ReminderMessage;
import com.habit66.notify.ReminderService;
import com.habit66.notify.ReminderService.Delivery;
import com.habit66.vocab.LoginProbe.LoginCheck;

class GeneratorHealthTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long DAY = 86_400_000L;

    private final Map<String, String> stored = new HashMap<>();
    private final SettingsStore settings = mock(SettingsStore.class);
    private final ReminderService notifier = mock(ReminderService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MutableClock wall = new MutableClock(Instant.parse("2026-11-16T03:00:00Z"));
    private final LogicalClock clock = new LogicalClock(wall, SEOUL, 3);

    /** 다음 점검이 돌려줄 결과. */
    private LoginCheck next = LoginCheck.ok(null);

    private final class FakeProbe implements TermGenerator, LoginProbe {
        @Override
        public Generated generate(Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginCheck checkLogin() {
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        when(settings.get(anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0, String.class))));
        doAnswer(inv -> stored.put(inv.getArgument(0), inv.getArgument(1))).when(settings).put(anyString(), anyString());
        when(notifier.deliver(any())).thenReturn(new Delivery(1, 0));
    }

    @Test
    void loginFailureAlertsOncePerDay() {
        GeneratorHealth health = health(new FakeProbe());
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);

        assertThat(health.view().state()).isEqualTo("LOGIN_REQUIRED");
        assertThat(health.view().message()).isEqualTo(ClaudeCliGenerator.LOGIN_HINT);
        ArgumentCaptor<ReminderMessage> sent = ArgumentCaptor.forClass(ReminderMessage.class);
        verify(notifier, times(1)).deliver(sent.capture());
        assertThat(sent.getValue().tag()).isEqualTo(GeneratorHealth.TAG);
        assertThat(sent.getValue().url()).isEqualTo("/?view=vocab");
        assertThat(sent.getValue().body()).contains("/login");

        wall.advance(DAY); // 다음 날에도 끊겨 있으면 한 번 더 알린다
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        verify(notifier, times(2)).deliver(any());
    }

    @Test
    void undeliveredAlertIsTriedAgain() {
        when(notifier.deliver(any())).thenReturn(new Delivery(0, 0));
        GeneratorHealth health = health(new FakeProbe());
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        verify(notifier, times(2)).deliver(any());
        assertThat(stored).doesNotContainKey(GeneratorHealth.ALERT_KEY);
    }

    @Test
    void tokenProblemTellsToMakeANewToken() {
        GeneratorHealth health = health(new FakeProbe());
        health.loginFailed(ClaudeCliGenerator.TOKEN_HINT);
        ArgumentCaptor<ReminderMessage> sent = ArgumentCaptor.forClass(ReminderMessage.class);
        verify(notifier).deliver(sent.capture());
        assertThat(sent.getValue().body()).contains("setup-token");
    }

    @Test
    void successAfterFailureRecoversAndRetries() {
        GeneratorHealth health = health(new FakeProbe());
        health.generated();
        verify(events, never()).publishEvent(any(Object.class));

        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        health.generated();
        assertThat(health.view().state()).isEqualTo("OK");
        verify(events).publishEvent(any(GeneratorHealth.Recovered.class));
    }

    @Test
    void checkFindsLoginProblemAndRecovery() {
        GeneratorHealth health = health(new FakeProbe());
        next = LoginCheck.loginRequired(ClaudeCliGenerator.LOGIN_HINT);
        assertThat(health.check().state()).isEqualTo("LOGIN_REQUIRED");

        next = LoginCheck.unknown("timed out"); // 네트워크 문제 등은 상태를 바꾸지 않는다
        assertThat(health.check().state()).isEqualTo("LOGIN_REQUIRED");

        next = LoginCheck.ok(null);
        assertThat(health.check().state()).isEqualTo("OK");
        verify(events).publishEvent(any(GeneratorHealth.Recovered.class));
    }

    @Test
    void warnsBeforeSubscriptionLoginExpires() {
        GeneratorHealth health = health(new FakeProbe());
        next = LoginCheck.ok(wall.millis() + 10 * DAY);
        assertThat(health.check().state()).isEqualTo("OK");
        verify(notifier, never()).deliver(any());

        next = LoginCheck.ok(wall.millis() + 2 * DAY);
        GeneratorHealth.View view = health.check();
        assertThat(view.state()).isEqualTo("EXPIRING");
        assertThat(view.message()).contains("11월 18일");
        ArgumentCaptor<ReminderMessage> sent = ArgumentCaptor.forClass(ReminderMessage.class);
        verify(notifier).deliver(sent.capture());
        assertThat(sent.getValue().title()).contains("곧 만료");
    }

    @Test
    void offWhenGeneratorIsNone() {
        GeneratorHealth health = health(new NoopGenerator());
        assertThat(health.check().state()).isEqualTo("OFF");
        health.loginFailed(ClaudeCliGenerator.LOGIN_HINT);
        assertThat(health.view().state()).isEqualTo("OFF");
        verify(notifier, never()).deliver(any());
    }

    private GeneratorHealth health(TermGenerator generator) {
        return new GeneratorHealth(generator, clock, settings, notifier, events, props());
    }

    private static AppProperties props() {
        return new AppProperties(
                "./build/test-data/health", "Asia/Seoul", 3, 66, "",
                new AppProperties.MorningLock("06:00", "09:30"),
                new AppProperties.Tts(false, "python -m edge_tts", "-10%", "a", "b", 60),
                new AppProperties.Push("", "", "mailto:test@example.com"),
                new AppProperties.Reminder(false, "0 0 22 * * *", "0 30 22 * * *", 3,
                        new AppProperties.Telegram(false, "", ""),
                        new AppProperties.Discord(false, "")),
                new AppProperties.Vocab("claude-cli", "claude", "haiku", 120, "", "0 0 12 * * *", 3));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long ms) {
            now = now.plusMillis(ms);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
