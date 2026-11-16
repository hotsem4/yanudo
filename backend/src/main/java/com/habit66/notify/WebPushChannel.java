package com.habit66.notify;

import java.security.Security;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.LogicalClock;
import com.habit66.config.AppProperties;
import com.habit66.notify.PushSubscriptionStore.Subscription;

import jakarta.annotation.PreDestroy;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;

/** VAPID Web Push. 푸시 서비스가 404/410 을 돌려주면 그 구독을 지운다. */
@Component
public class WebPushChannel implements ReminderChannel {

    private static final Logger log = LoggerFactory.getLogger(WebPushChannel.class);
    private static final int TIMEOUT_SECONDS = 20;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PushSubscriptionStore store;
    private final ObjectMapper mapper;
    private final PushService service;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "web-push");
        t.setDaemon(true);
        return t;
    });

    private final LogicalClock clock;

    public WebPushChannel(PushSubscriptionStore store, VapidKeys keys, AppProperties props, ObjectMapper mapper,
            LogicalClock clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        PushService created = null;
        try {
            if (!keys.available()) {
                throw new IllegalStateException("VAPID key file unreadable");
            }
            created = new PushService(keys.publicKey(), keys.privateKey(), props.push().subject());
        } catch (Exception e) {
            log.error("Web Push is disabled: VAPID keys cannot be loaded ({})", e.toString());
        }
        this.service = created;
    }

    @Override
    public Kind kind() {
        return Kind.PUSH;
    }

    @Override
    public int send(ReminderMessage message) {
        if (service == null) {
            return 0;
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(message);
        } catch (Exception e) {
            log.error("Cannot serialize push payload", e);
            return 0;
        }
        int sent = 0;
        for (Subscription sub : store.all()) {
            try {
                // 긴급도 HIGH: 폰이 대기(Doze) 중이어도 바로 깨워 전달한다. 유효 시간은 그날이 끝날 때(롤오버)까지:
                // 폰이 꺼져 있다가 다음 날 켜졌을 때 어제의 마감 알림이 뒤늦게 오지 않게 한다.
                Notification notification = Notification.builder()
                        .endpoint(sub.endpoint())
                        .userPublicKey(sub.p256dh())
                        .userAuth(sub.auth())
                        .payload(payload)
                        .ttl(ttlSeconds())
                        .urgency(Urgency.HIGH)
                        .build();
                // 라이브러리의 비동기 전송을 직접 기다리고, 시간이 넘으면 그 요청 자체를 취소한다.
                Future<HttpResponse> pending = service.sendAsync(notification, Encoding.AES128GCM);
                int status;
                try {
                    status = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatusLine().getStatusCode();
                } finally {
                    pending.cancel(true);
                }
                if (status >= 200 && status < 300) {
                    sent++;
                } else if (status == 404 || status == 410) {
                    store.expire(sub.endpoint(), clock.now());
                    log.info("Removed expired push subscription (HTTP {})", status);
                } else if (status == 401 || status == 403) {
                    // 서버 키(VAPID)와 구독이 맞지 않는다: 키가 바뀌었거나 구독이 다른 키로 만들어졌다. 앱이 열리면 다시 구독한다.
                    log.warn("Push service rejected the VAPID signature (HTTP {}); the subscription needs renewing", status);
                } else {
                    log.warn("Push service answered HTTP {}", status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            } catch (Exception e) {
                // 예외 메시지에 구독 주소(사실상 비밀 주소)가 섞일 수 있어 종류만 남긴다.
                log.warn("Web Push failed: {}", e.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** 그날이 끝날 때(다음 롤오버)까지 남은 초. 최소 1분, 최대 6시간. */
    int ttlSeconds() {
        long until = clock.bounds(clock.today()).until();
        long seconds = (until - clock.now()) / 1000;
        return (int) Math.max(60, Math.min(6 * 3600, seconds));
    }

    @PreDestroy
    void stop() {
        io.shutdownNow();
    }
}
