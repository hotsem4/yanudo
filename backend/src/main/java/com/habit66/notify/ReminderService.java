package com.habit66.notify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.habit66.common.LogicalClock;
import com.habit66.common.WriteLock;
import com.habit66.config.AppProperties;
import com.habit66.notify.ReminderChannel.Kind;
import com.habit66.progress.DaySnapshot;
import com.habit66.progress.DayStateService;

/**
 * 22:00 마감 리마인드. reminder_log 의 유일한 쓰기 소유자다.
 * 서버에 도착한 기록 기준으로 오늘이 미달성일 때만, 하루 한 번(모두 실패하면 max-attempts 회까지) 보낸다.
 */
@Service
public class ReminderService {

    public record Delivery(int pushSent, int webhooksSent) {

        boolean delivered() {
            return pushSent + webhooksSent > 0;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final LocalTime CATCH_UP_FROM = LocalTime.of(22, 0);

    private final AppProperties.Reminder config;
    private final LogicalClock clock;
    private final DayStateService dayState;
    private final List<ReminderChannel> channels;
    private final JdbcTemplate jdbc;
    private final WriteLock writes;

    public ReminderService(AppProperties props, LogicalClock clock, DayStateService dayState,
            List<ReminderChannel> channels, JdbcTemplate jdbc, WriteLock writes) {
        this.writes = writes;
        this.config = props.reminder();
        this.clock = clock;
        this.dayState = dayState;
        this.channels = channels;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${app.reminder.cron}", zone = "${app.timezone}")
    public void atDeadline() {
        remind("deadline");
    }

    @Scheduled(cron = "${app.reminder.retry-cron}", zone = "${app.timezone}")
    public void retry() {
        remind("retry");
    }

    /** PC 가 22시에 꺼져 있었으면 켜진 뒤(22:00~롤오버 사이)에 한 번 보충한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        LocalTime wall = clock.wallTime(clock.now());
        if (!wall.isBefore(CATCH_UP_FROM) || wall.getHour() < clock.rolloverHour()) {
            Thread thread = new Thread(() -> remind("startup"), "reminder-catch-up");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** 모든 채널로 보낸다. 채널 하나가 실패해도 나머지는 계속한다. */
    public Delivery deliver(ReminderMessage message) {
        int push = 0;
        int webhooks = 0;
        for (ReminderChannel channel : channels) {
            int sent;
            try {
                sent = channel.send(message);
            } catch (RuntimeException e) {
                log.warn("Reminder channel {} failed: {}", channel.getClass().getSimpleName(), e.toString());
                sent = 0;
            }
            if (channel.kind() == Kind.PUSH) {
                push += sent;
            } else {
                webhooks += sent;
            }
        }
        return new Delivery(push, webhooks);
    }

    synchronized void remind(String trigger) {
        if (!config.enabled()) {
            return;
        }
        try {
            DaySnapshot snapshot = dayState.snapshot();
            if (snapshot.streak().achievedToday() || snapshot.restDay().declaredToday()
                    || snapshot.todayLesson() == null) {
                return;
            }
            LocalDate today = snapshot.today();
            if (!claim(today)) {
                return;
            }
            Delivery delivery = deliver(messageFor(snapshot));
            writes.run(() -> jdbc.update("""
                    UPDATE reminder_log SET status = ?, push_sent = ?, webhooks_sent = ?, updated_at = ?
                    WHERE log_date = ?
                    """, delivery.delivered() ? "SENT" : "FAILED", delivery.pushSent(), delivery.webhooksSent(),
                    clock.now(), today.toString()));
            log.info("Reminder ({}) for {}: push={}, webhooks={}", trigger, today, delivery.pushSent(),
                    delivery.webhooksSent());
        } catch (RuntimeException e) {
            log.error("Reminder run failed", e);
        }
    }

    /** 보내기 전에 SENDING 으로 선점한다. 이미 보냈거나 시도 횟수를 다 썼으면 false. */
    private boolean claim(LocalDate date) {
        return writes.call(() -> claimLocked(date));
    }

    private boolean claimLocked(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts FROM reminder_log WHERE log_date = ?", date.toString());
        long now = clock.now();
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO reminder_log (log_date, status, attempts, push_sent, webhooks_sent, updated_at)
                    VALUES (?, 'SENDING', 1, 0, 0, ?)
                    """, date.toString(), now);
            return true;
        }
        String status = String.valueOf(rows.get(0).get("status"));
        int attempts = ((Number) rows.get(0).get("attempts")).intValue();
        if ("SENT".equals(status) || attempts >= config.maxAttempts()) {
            return false;
        }
        jdbc.update("UPDATE reminder_log SET status = 'SENDING', attempts = attempts + 1, updated_at = ? "
                + "WHERE log_date = ?", now, date.toString());
        return true;
    }

    private static ReminderMessage messageFor(DaySnapshot snapshot) {
        int left = snapshot.todayLesson().sentences().size() - snapshot.spokenSeenIds().size();
        String streak = snapshot.streak().current() > 0
                ? snapshot.streak().current() + "일 연속 기록이 걸려 있어요. "
                : "";
        return new ReminderMessage(
                "Day " + snapshot.currentDay() + " · 오늘의 10문장이 남았어요",
                streak + left + "문장 남았어요. 탭해서 따라 말해 보세요."
                        + (snapshot.restDay().left() > 0 ? " 어려우면 '오늘 할 일'에서 쉬는 날로 알려요." : ""),
                ReminderMessage.TODAY_URL,
                ReminderMessage.TAG);
    }
}
