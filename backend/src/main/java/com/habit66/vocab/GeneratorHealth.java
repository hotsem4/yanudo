package com.habit66.vocab;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.config.AppProperties;
import com.habit66.notify.ReminderMessage;
import com.habit66.notify.ReminderService;
import com.habit66.notify.ReminderService.Delivery;
import com.habit66.vocab.LoginProbe.LoginCheck;

import jakarta.annotation.PreDestroy;

/**
 * 생성기 로그인 상태(SPEC F-08). 상태는 메모리에만 두고(파생값), 알림을 보낸 날만 app_setting 에 남겨
 * 같은 알림을 하루 한 번 넘게 보내지 않는다(서버를 다시 켜도).
 *
 * <ul>
 * <li>생성이 로그인 문제로 실패하거나, 하루 한 번 점검({@code app.vocab.login-check-cron}, 시작 때도)이 로그인 문제를 찾으면
 * LOGIN_REQUIRED 로 두고 폰(웹 푸시·웹훅)에 알린다.</li>
 * <li>구독 로그인의 만료 예정이 {@code app.vocab.login-warn-days}일 안이면 EXPIRING 으로 미리 알린다(장기 토큰을 쓰면 만료 시각을 알 수 없어 생략).</li>
 * <li>다시 성공하면 OK 로 돌리고 {@link Recovered}를 알려 실패한 표현을 다시 만들게 한다.</li>
 * </ul>
 */
@Component
public class GeneratorHealth {

    public enum State { OK, EXPIRING, LOGIN_REQUIRED, OFF }

    /** {@code GET /vocab}의 {@code generator}. */
    public record View(String state, String message, Long expiresAt) {
    }

    /** 로그인 문제가 풀렸다(생성 성공 또는 점검 통과). */
    public record Recovered() {
    }

    static final String ALERT_KEY = "vocab_login_alert";
    static final String TAG = "habit66-vocab-login";
    static final String URL = "/?view=vocab";

    private static final Logger log = LoggerFactory.getLogger(GeneratorHealth.class);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

    private final TermGenerator generator;
    private final LogicalClock clock;
    private final SettingsStore settings;
    private final ReminderService notifier;
    private final ApplicationEventPublisher events;
    private final int warnDays;

    /** 시작 점검용. 서버를 끌 때 기다려 claude 프로세스 정리(finally)가 끝나게 한다. */
    private final ExecutorService startupCheck = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vocab-login-check");
        t.setDaemon(true);
        return t;
    });
    /** 알림의 "오늘 보냈나 → 보내기 → 기록"을 한 번에 한 스레드만 하게 한다(큐 스레드와 점검 스레드가 동시에 실패를 볼 수 있다). */
    private final Object alertLock = new Object();

    private volatile State state;
    private volatile String message;
    private volatile Long expiresAt;

    public GeneratorHealth(TermGenerator generator, LogicalClock clock, SettingsStore settings,
            ReminderService notifier, ApplicationEventPublisher events, AppProperties props) {
        this.generator = generator;
        this.clock = clock;
        this.settings = settings;
        this.notifier = notifier;
        this.events = events;
        this.warnDays = props.vocab().loginWarnDays();
        this.state = generator.enabled() ? State.OK : State.OFF;
    }

    public View view() {
        return new View(state.name(), message, expiresAt);
    }

    /** 생성이 사용자 조치가 필요한 이유로 실패했다(큐가 부른다). */
    public void loginFailed(String hint) {
        if (state == State.OFF) {
            return;
        }
        boolean changed = state != State.LOGIN_REQUIRED;
        state = State.LOGIN_REQUIRED;
        message = hint;
        if (changed) {
            log.warn("Vocab generator needs login: {}", hint);
        }
        alert(State.LOGIN_REQUIRED, "단어 뜻 만들기가 멈췄어요", bodyFor(hint));
    }

    /** 원인별 알림 본문. 로그인 문제가 아닌 사용자 조치(명령 없음, API 키)는 힌트를 그대로 싣는다. */
    static String bodyFor(String hint) {
        if (ClaudeCliGenerator.TOKEN_HINT.equals(hint)) {
            return "PC에 넣어 둔 Claude 장기 토큰이 만료됐거나 잘못됐어요. claude setup-token 으로 새로 만들어 넣어 주세요.";
        }
        if (hint == null || ClaudeCliGenerator.LOGIN_HINT.equals(hint)) {
            return "PC의 Claude 로그인이 끊겼어요. PC에서 claude 를 실행해 /login 하거나 장기 토큰을 넣어 주세요.";
        }
        return "PC에서 확인이 필요해요: " + hint;
    }

    /** 생성이 성공했다(큐가 부른다). */
    public void generated() {
        if (state == State.LOGIN_REQUIRED) {
            recover();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (!(generator instanceof LoginProbe) || !generator.enabled()) {
            return;
        }
        startupCheck.execute(this::check);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        startupCheck.shutdownNow();
        startupCheck.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Scheduled(cron = "${app.vocab.login-check-cron}", zone = "${app.timezone}")
    public void scheduledCheck() {
        check();
    }

    /** 지금 점검한다. 생성기가 로그인을 쓰지 않으면 아무것도 하지 않는다. */
    public synchronized View check() {
        if (!(generator instanceof LoginProbe probe) || !generator.enabled()) {
            return view();
        }
        LoginCheck result = probe.checkLogin();
        switch (result.result()) {
            case LOGIN_REQUIRED -> loginFailed(result.hint());
            case OK -> {
                if (state == State.LOGIN_REQUIRED) {
                    recover();
                }
                expiresAt = result.expiresAt();
                if (expiresSoon(result.expiresAt())) {
                    String day = Instant.ofEpochMilli(result.expiresAt()).atZone(clock.zone()).format(MONTH_DAY);
                    state = State.EXPIRING;
                    message = "PC의 Claude 로그인이 " + day + "에 만료돼요";
                    alert(State.EXPIRING, "Claude 로그인이 곧 만료돼요",
                            day + "에 만료돼요. PC에서 다시 로그인하거나 장기 토큰을 넣어 두면 단어 뜻 만들기가 멈추지 않아요.");
                } else {
                    state = State.OK;
                    message = null;
                }
            }
            case UNKNOWN -> log.info("Vocab login check inconclusive: {}", result.hint());
        }
        return view();
    }

    private boolean expiresSoon(Long at) {
        return at != null && at - clock.now() < warnDays * 86_400_000L;
    }

    private void recover() {
        state = State.OK;
        message = null;
        log.info("Vocab generator login is back; retrying failed terms");
        events.publishEvent(new Recovered());
    }

    /** 같은 종류의 알림은 논리 날짜 하루에 한 번. 아무 채널로도 못 보냈으면 기록하지 않아 다음 기회에 다시 보낸다. */
    private void alert(State kind, String title, String body) {
        synchronized (alertLock) {
            String stamp = kind.name() + "|" + clock.today();
            if (settings.get(ALERT_KEY).map(stamp::equals).orElse(false)) {
                return;
            }
            Delivery delivery = notifier.deliver(new ReminderMessage(title, body, URL, TAG));
            if (delivery.pushSent() + delivery.webhooksSent() > 0) {
                settings.put(ALERT_KEY, stamp);
                log.info("Sent vocab login alert ({}): push={}, webhooks={}", kind, delivery.pushSent(),
                        delivery.webhooksSent());
            } else {
                log.info("Vocab login alert ({}) not delivered: no push subscription or webhook", kind);
            }
        }
    }
}
