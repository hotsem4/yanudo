package com.habit66.notify;

import java.util.Base64;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.ApiException;
import com.habit66.common.LogicalClock;
import com.habit66.notify.PushSubscriptionStore.Subscription;
import com.habit66.notify.ReminderService.Delivery;

@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    /** 브라우저 PushSubscription.toJSON() 그대로. */
    public record SubscribeRequest(String endpoint, Keys keys) {
    }

    public record Keys(String p256dh, String auth) {
    }

    public record UnsubscribeRequest(String endpoint) {
    }

    private final VapidKeys keys;
    private final PushSubscriptionStore store;
    private final ReminderService reminders;
    private final LogicalClock clock;

    public PushController(VapidKeys keys, PushSubscriptionStore store, ReminderService reminders,
            LogicalClock clock) {
        this.keys = keys;
        this.store = store;
        this.reminders = reminders;
        this.clock = clock;
    }

    @GetMapping("/vapid-public-key")
    public Map<String, String> vapidPublicKey() {
        return Map.of("public_key", keys.publicKey());
    }

    @PostMapping("/subscribe")
    public Map<String, Boolean> subscribe(@RequestBody SubscribeRequest body,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        // 키가 깨진 구독은 푸시 서비스에 닿기도 전에 실패해 404/410 정리 대상이 되지 못하므로 저장할 때 거른다.
        if (blank(body.endpoint()) || !body.endpoint().startsWith("https://") || body.keys() == null
                || decodedLength(body.keys().p256dh()) != 65 || decodedLength(body.keys().auth()) != 16) {
            throw ApiException.badRequest("INVALID_SUBSCRIPTION",
                    "구독 정보(endpoint, keys.p256dh, keys.auth)가 올바르지 않습니다.");
        }
        // 이미 만료라고 들은 주소면 받지 않는다. 앱은 이 답을 받으면 브라우저 구독을 새로 만들어 다시 등록한다.
        if (store.isExpired(body.endpoint())) {
            return Map.of("subscribed", false, "expired", true);
        }
        store.upsert(new Subscription(body.endpoint(), body.keys().p256dh(), body.keys().auth()), userAgent,
                clock.now());
        return Map.of("subscribed", true, "expired", false);
    }

    @PostMapping("/unsubscribe")
    public Map<String, Boolean> unsubscribe(@RequestBody UnsubscribeRequest body) {
        if (blank(body.endpoint())) {
            throw ApiException.badRequest("INVALID_SUBSCRIPTION", "endpoint 가 필요합니다.");
        }
        return Map.of("removed", store.delete(body.endpoint()));
    }

    @PostMapping("/test")
    public Delivery test() {
        return reminders.deliver(new ReminderMessage(
                "66 Habit English",
                "시험 알림이에요. 밤 10시에 오늘 암송이 남아 있으면 이렇게 알려 드려요.",
                ReminderMessage.TODAY_URL,
                ReminderMessage.TAG));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** base64url(브라우저 기본) 또는 표준 base64 로 풀었을 때의 바이트 수. 풀 수 없으면 -1. */
    private static int decodedLength(String value) {
        if (blank(value)) {
            return -1;
        }
        String unpadded = value.trim().replace("=", "");
        try {
            return Base64.getUrlDecoder().decode(unpadded).length;
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getDecoder().decode(unpadded).length;
            } catch (IllegalArgumentException e2) {
                return -1;
            }
        }
    }
}
