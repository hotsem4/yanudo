package com.habit66.notify;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.habit66.common.WriteLock;

/** push_subscription·push_expired 테이블의 유일한 쓰기 소유자. */
@Repository
public class PushSubscriptionStore {

    public record Subscription(String endpoint, String p256dh, String auth) {
    }

    private final JdbcTemplate jdbc;
    private final WriteLock writes;

    public PushSubscriptionStore(JdbcTemplate jdbc, WriteLock writes) {
        this.jdbc = jdbc;
        this.writes = writes;
    }

    public void upsert(Subscription sub, String userAgent, long now) {
        writes.run(() -> jdbc.update("""
                INSERT INTO push_subscription (endpoint, p256dh, auth, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(endpoint) DO UPDATE SET
                    p256dh = excluded.p256dh, auth = excluded.auth, user_agent = excluded.user_agent
                """, sub.endpoint(), sub.p256dh(), sub.auth(), userAgent, now));
    }

    public boolean delete(String endpoint) {
        return writes.call(() -> jdbc.update("DELETE FROM push_subscription WHERE endpoint = ?", endpoint) > 0);
    }

    /** 푸시 서비스가 만료라고 답한 구독: 지우고, 같은 주소가 다시 등록되지 않게 기억한다. */
    public void expire(String endpoint, long now) {
        writes.run(() -> {
            jdbc.update("DELETE FROM push_subscription WHERE endpoint = ?", endpoint);
            jdbc.update("INSERT OR REPLACE INTO push_expired (endpoint, expired_at) VALUES (?, ?)", endpoint, now);
        });
    }

    public boolean isExpired(String endpoint) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM push_expired WHERE endpoint = ?", Integer.class, endpoint);
        return n != null && n > 0;
    }

    public List<Subscription> all() {
        return jdbc.query("SELECT endpoint, p256dh, auth FROM push_subscription ORDER BY id",
                (rs, i) -> new Subscription(rs.getString(1), rs.getString(2), rs.getString(3)));
    }
}
