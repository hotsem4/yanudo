package com.habit66.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.habit66.common.ApiException;
import com.habit66.progress.ClientEvent.LockResult;
import com.habit66.progress.ClientEvent.MarkStatus;
import com.habit66.progress.ClientEvent.Session;
import com.habit66.progress.ClientEvent.Via;

/**
 * POST /events 본문. 이벤트를 JSON 노드로 받아 하나씩 검증하므로, 형식이 틀린 이벤트는 그것만 거절된다.
 */
public record EventsRequest(List<JsonNode> events) {

    public record Rejected(String id, String reason) {
    }

    public record Parsed(List<ClientEvent> events, List<Rejected> rejected) {
    }

    public static final int MAX_EVENTS = 500;
    public static final int MAX_FOCUS_SECONDS = 21_600;

    /** SPEC 은 [A-Za-z0-9:_-] 이지만, 타이머 id(focus:&lt;시작 시각&gt;)에 소수점이 붙어 올 수 있어 '.' 도 받는다. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9:._-]{1,64}");

    public Parsed parse() {
        if (events == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "events 배열이 필요합니다.");
        }
        if (events.size() > MAX_EVENTS) {
            throw ApiException.badRequest("TOO_MANY_EVENTS", "한 번에 " + MAX_EVENTS + "개까지만 보낼 수 있습니다.");
        }
        List<ClientEvent> ok = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (JsonNode node : events) {
            String id = node != null && node.path("id").isTextual() ? node.path("id").asText() : "";
            if (!ID.matcher(id).matches()) {
                rejected.add(new Rejected(id.length() > 64 ? id.substring(0, 64) : id, "INVALID_ID"));
                continue;
            }
            try {
                ok.add(toEvent(id, node));
            } catch (Invalid e) {
                rejected.add(new Rejected(id, e.reason));
            }
        }
        return new Parsed(ok, rejected);
    }

    private static ClientEvent toEvent(String id, JsonNode node) {
        String type = node.path("type").asText("");
        long occurredAt = millis(node, "occurred_at");
        switch (type) {
            case "VIEW":
                return new ClientEvent.View(id, occurredAt, positive(node, "sentence_id"),
                        choice(Session.class, node, "session"), choice(Via.class, node, "via"));
            case "MARK":
                return new ClientEvent.Mark(id, occurredAt, positive(node, "sentence_id"),
                        choice(MarkStatus.class, node, "status"));
            case "FOCUS": {
                long seconds = positive(node, "seconds");
                if (seconds > MAX_FOCUS_SECONDS) {
                    throw new Invalid("INVALID_FIELD");
                }
                return new ClientEvent.Focus(id, occurredAt, millis(node, "started_at"), (int) seconds);
            }
            case "MORNING_LOCK":
                return new ClientEvent.MorningLock(id, occurredAt, choice(LockResult.class, node, "result"));
            default:
                throw new Invalid("INVALID_TYPE");
        }
    }

    private static long positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new Invalid("INVALID_FIELD");
        }
        return value.asLong();
    }

    /**
     * 시각(epoch ms). 클라이언트의 서버 시계 추정값은 왕복 시간의 절반을 더하므로 .5 로 끝날 수 있다.
     * 거절하면 그 학습 기록이 아웃박스에서 지워지므로 소수부를 버리고 받는다.
     */
    private static long millis(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new Invalid("INVALID_FIELD");
        }
        return value.asLong();
    }

    private static <E extends Enum<E>> E choice(Class<E> type, JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value.asText())) {
                    return constant;
                }
            }
        }
        throw new Invalid("INVALID_FIELD");
    }

    private static final class Invalid extends RuntimeException {

        private final String reason;

        private Invalid(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
