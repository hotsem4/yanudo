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
import com.habit66.config.AppProperties;
import com.habit66.notify.PushSubscriptionStore.Subscription;

import jakarta.annotation.PreDestroy;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;

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

    public WebPushChannel(PushSubscriptionStore store, VapidKeys keys, AppProperties props, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        PushService created = null;
        try {
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
                Notification notification = new Notification(sub.endpoint(), sub.p256dh(), sub.auth(), payload);
                // 라이브러리가 응답을 기한 없이 기다리므로 바깥에서 끊는다.
                Future<HttpResponse> pending = io.submit(() -> service.send(notification, Encoding.AES128GCM));
                int status;
                try {
                    status = pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatusLine().getStatusCode();
                } finally {
                    pending.cancel(true);
                }
                if (status >= 200 && status < 300) {
                    sent++;
                } else if (status == 404 || status == 410) {
                    store.delete(sub.endpoint());
                    log.info("Removed expired push subscription (HTTP {})", status);
                } else {
                    log.warn("Push service answered HTTP {}", status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            } catch (Exception e) {
                log.warn("Web Push failed: {}", e.toString());
            }
        }
        return sent;
    }

    @PreDestroy
    void stop() {
        io.shutdownNow();
    }
}
