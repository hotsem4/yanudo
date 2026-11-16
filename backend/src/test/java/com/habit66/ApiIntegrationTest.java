package com.habit66;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habit66.tts.Mp3Samples;

/**
 * 임시 data-dir 위에서 실제 SQLite·스키마·시드로 API 계약(SPEC 4장)을 확인한다. 시계는 고정했다가 테스트가 옮긴다.
 * 하루의 흐름을 따라가므로 순서대로 실행한다.
 */
@SpringBootTest(properties = {
        "app.tts.auto-generate=false",
        "app.reminder.enabled=false",
        "app.vocab.generator=none",
        "app.admin-token=",
        "app.push.public-key=",
        "app.push.private-key="
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final MutableClock CLOCK = new MutableClock(seoul(15, 10, 0));
    private static final Path DATA_DIR =
            Paths.get("build", "test-data", "it-" + System.nanoTime()).toAbsolutePath().normalize();

    static {
        // 환경 후처리기(DataDirEnvironmentPostProcessor)가 읽을 수 있도록 컨텍스트가 뜨기 전에 정한다.
        System.setProperty("app.data-dir", DATA_DIR.toString().replace('\\', '/'));
    }

    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ── 1. 첫 실행 ────────────────────────────────────────────

    @Test
    @Order(1)
    void seedsFiftySentencesAndStartsOnDayOne() throws Exception {
        JsonNode d = getJson("/api/v1/dashboard");

        assertThat(d.path("today").asText()).isEqualTo("2026-11-15");
        assertThat(d.path("server_time").asLong()).isEqualTo(CLOCK.millis());
        assertThat(d.path("valid_from").asLong()).isEqualTo(seoul(15, 3, 0));
        assertThat(d.path("valid_until").asLong()).isEqualTo(seoul(16, 3, 0));
        assertThat(d.path("current_day").asInt()).isEqualTo(1);
        assertThat(d.path("total_days").asInt()).isEqualTo(66);
        assertThat(d.path("lesson_day").asInt()).isEqualTo(1);
        assertThat(d.path("max_lesson_day").asInt()).isEqualTo(5);
        assertThat(d.path("content_missing").asBoolean()).isFalse();
        assertThat(d.path("program_complete").asBoolean()).isFalse();

        JsonNode streak = d.path("streak");
        assertThat(streak.path("current").asInt()).isZero();
        assertThat(streak.path("if_achieved_today").asInt()).isEqualTo(1);
        assertThat(streak.path("longest").asInt()).isZero();
        assertThat(streak.path("achieved_today").asBoolean()).isFalse();
        assertThat(streak.path("freezes_left").asInt()).isEqualTo(2);
        assertThat(streak.path("freezes_per_period").asInt()).isEqualTo(2);

        assertThat(d.path("today_progress").path("total").asInt()).isEqualTo(10);
        assertThat(d.path("today_progress").path("sentence_ids")).hasSize(10);
        assertThat(d.path("today_progress").path("audio_seen_ids")).isEmpty();
        assertThat(d.path("today_progress").path("card_seen_ids")).isEmpty();

        JsonNode gate = d.path("review_gate");
        assertThat(gate.path("required").asBoolean()).isFalse();
        assertThat(gate.path("cleared").asBoolean()).isTrue();
        assertThat(gate.path("yesterday_day").isNull()).isTrue();
        assertThat(gate.path("unlock_ids")).isEmpty();
        assertThat(gate.path("seen_review_ids")).isEmpty();

        assertThat(ints(d.path("review_progress").path("days"))).containsExactly(1);
        assertThat(d.path("review_progress").path("total").asInt()).isEqualTo(10);
        assertThat(d.path("review_progress").path("seen").asInt()).isZero();

        JsonNode lock = d.path("morning_lock");
        assertThat(lock.path("window_start").asLong()).isEqualTo(seoul(15, 6, 0));
        assertThat(lock.path("window_end").asLong()).isEqualTo(seoul(15, 9, 30));
        assertThat(lock.path("state").asText()).isEqualTo("NONE");
        assertThat(lock.path("sentences")).hasSize(3);
        assertThat(lock.path("sentences")).allSatisfy(s -> assertThat(s.path("core").asBoolean()).isTrue());

        assertThat(d.path("focus").path("seconds_today").asLong()).isZero();
        assertThat(d.path("focus").path("started_ats")).isEmpty();

        JsonNode status = getJson("/api/v1/admin/tts/status");
        assertThat(status.path("clips_total").asInt()).isEqualTo(50);
        assertThat(status.path("clips_missing").asInt()).isEqualTo(50);
        assertThat(status.path("running").asBoolean()).isFalse();
        assertThat(status.path("queued").asInt()).isZero();
        assertThat(status.path("done").asInt()).isZero();
        assertThat(status.path("failed").asInt()).isZero();
        assertThat(status.has("last_error")).isTrue();
        assertThat(status.path("last_error").isNull()).isTrue();

        JsonNode settings = getJson("/api/v1/admin/settings");
        assertThat(settings.path("start_date").asText()).isEqualTo("2026-11-15");
        assertThat(settings.path("timezone").asText()).isEqualTo("Asia/Seoul");
        assertThat(settings.path("day_rollover_hour").asInt()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void sessionsCarryTheSentenceShapeOfTheSpec() throws Exception {
        JsonNode today = getJson("/api/v1/sessions/today");
        assertThat(today.path("session").asText()).isEqualTo("TODAY");
        assertThat(ints(today.path("days"))).containsExactly(1);
        assertThat(today.path("title").asText()).isEqualTo("처음 만난 사람과 인사하기");
        assertThat(today.path("preview").asBoolean()).isFalse();
        assertThat(today.path("sentences")).hasSize(10);

        JsonNode first = today.path("sentences").get(0);
        assertThat(fieldNames(first)).containsExactly("id", "day", "seq", "speaker", "en_text", "ko_word_order",
                "ko_natural", "core", "status", "status_changed_at", "audio_url", "duration_ms",
                "duration_estimated", "terms");
        assertThat(first.path("day").asInt()).isEqualTo(1);
        assertThat(first.path("seq").asInt()).isEqualTo(1);
        assertThat(first.path("speaker").asText()).isEqualTo("A");
        assertThat(first.path("en_text").asText()).isEqualTo("Hi, I don't think we've met before.");
        assertThat(first.path("ko_natural").asText()).isEqualTo("안녕하세요, 처음 뵙는 것 같네요.");
        assertThat(first.path("core").asBoolean()).isTrue();
        assertThat(first.path("status").asText()).isEqualTo("NEW");
        assertThat(first.path("status_changed_at").isNull()).isTrue();
        // 클립이 아직 없으면 길이는 단어 수 × 380ms 추정이다.
        assertThat(first.path("audio_url").isNull()).isTrue();
        assertThat(first.path("duration_ms").asLong()).isEqualTo(7 * 380);
        assertThat(first.path("duration_estimated").asBoolean()).isTrue();

        // Day 1 은 복습할 이전 일차가 없어 자기 자신을 미리보기로 복습한다.
        JsonNode review = getJson("/api/v1/sessions/review");
        assertThat(review.path("session").asText()).isEqualTo("REVIEW");
        assertThat(review.path("preview").asBoolean()).isTrue();
        assertThat(ints(review.path("days"))).containsExactly(1);
        assertThat(review.path("sentences")).hasSize(10);

        JsonNode heatmap = getJson("/api/v1/habit/heatmap");
        assertThat(heatmap.path("start_date").asText()).isEqualTo("2026-11-15");
        assertThat(heatmap.path("current_day").asInt()).isEqualTo(1);
        assertThat(heatmap.path("cells")).hasSize(66);
        assertThat(heatmap.path("cells").get(0).path("day").asInt()).isEqualTo(1);
        assertThat(heatmap.path("cells").get(0).path("date").asText()).isEqualTo("2026-11-15");
        assertThat(heatmap.path("cells").get(0).path("status").asText()).isEqualTo("TODAY");
        assertThat(heatmap.path("cells").get(65).path("date").asText()).isEqualTo("2027-01-19");
        assertThat(heatmap.path("cells").get(65).path("status").asText()).isEqualTo("FUTURE");
    }

    // ── 2. 오늘 달성과 멱등 ───────────────────────────────────

    @Test
    @Order(3)
    void tenCardViewsAchieveTodayAndResendingChangesNothing() throws Exception {
        List<Long> ids = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids"));
        ArrayNode events = mapper.createArrayNode();
        for (int i = 0; i < ids.size(); i++) {
            events.add(view("card-" + i, ids.get(i), "TODAY", "CARD", CLOCK.millis()));
        }

        // 9개까지는 달성이 아니다.
        ArrayNode nine = mapper.createArrayNode();
        for (int i = 0; i < 9; i++) {
            nine.add(events.get(i));
        }
        JsonNode partial = postEvents(nine);
        assertThat(partial.path("accepted")).hasSize(9);
        assertThat(partial.path("dashboard").path("streak").path("achieved_today").asBoolean()).isFalse();

        String firstBody = postRaw("/api/v1/events", eventsBody(events), 200);
        JsonNode first = mapper.readTree(firstBody);
        assertThat(texts(first.path("accepted"))).hasSize(10).contains("card-0", "card-9");
        assertThat(first.path("rejected")).isEmpty();
        JsonNode dashboard = first.path("dashboard");
        assertThat(dashboard.path("streak").path("achieved_today").asBoolean()).isTrue();
        assertThat(dashboard.path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(dashboard.path("streak").path("if_achieved_today").asInt()).isEqualTo(1);
        assertThat(dashboard.path("streak").path("longest").asInt()).isEqualTo(1);
        assertThat(longs(dashboard.path("today_progress").path("card_seen_ids"))).containsExactlyElementsOf(ids);
        assertThat(dashboard.path("today_progress").path("audio_seen_ids")).isEmpty();

        // 같은 배치를 다시 보내도 응답이 글자 하나까지 같다.
        assertThat(postRaw("/api/v1/events", eventsBody(events), 200)).isEqualTo(firstBody);

        JsonNode heatmap = getJson("/api/v1/habit/heatmap");
        assertThat(heatmap.path("cells").get(0).path("status").asText()).isEqualTo("ACHIEVED");
        assertThat(heatmap.path("achieved_days").asInt()).isEqualTo(1);
        assertThat(heatmap.path("current_streak").asInt()).isEqualTo(1);
    }

    // ── 3. 이벤트별 거절과 나머지 사실 ────────────────────────

    @Test
    @Order(4)
    void rejectsOnlyTheBadEventsAndAcceptsTheRest() throws Exception {
        long sentenceId = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids")).get(0);
        long now = CLOCK.millis();
        ArrayNode events = mapper.createArrayNode();
        events.add(view("ghost", 999_999L, "TODAY", "CARD", now));
        events.add(event("weird", "NOPE", now));
        events.add(view("bad id!", sentenceId, "TODAY", "CARD", now));
        events.add(view("bad-via", sentenceId, "TODAY", "TELEPATHY", now));
        events.add(event("no-time", "MORNING_LOCK", 0).put("result", "CLEARED"));
        events.add(event("mark-1", "MARK", now).put("sentence_id", sentenceId).put("status", "MEMORIZED"));

        JsonNode res = postEvents(events);

        assertThat(texts(res.path("accepted"))).containsExactly("mark-1");
        assertThat(reasonOf(res, "ghost")).isEqualTo("UNKNOWN_SENTENCE");
        assertThat(reasonOf(res, "weird")).isEqualTo("INVALID_TYPE");
        assertThat(reasonOf(res, "bad id!")).isEqualTo("INVALID_ID");
        assertThat(reasonOf(res, "bad-via")).isEqualTo("INVALID_FIELD");
        assertThat(reasonOf(res, "no-time")).isEqualTo("INVALID_FIELD");
        assertThat(res.path("rejected")).hasSize(5);
    }

    @Test
    @Order(5)
    void markKeepsTheNewestChange() throws Exception {
        long sentenceId = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids")).get(0);
        long markedAt = CLOCK.millis();

        // 더 오래된 변경이 늦게 도착해도 덮지 못한다.
        postEvents(array(event("mark-old", "MARK", markedAt - 5_000).put("sentence_id", sentenceId)
                .put("status", "PRACTICE")));
        JsonNode sentence = getJson("/api/v1/sessions/today").path("sentences").get(0);
        assertThat(sentence.path("status").asText()).isEqualTo("MEMORIZED");
        assertThat(sentence.path("status_changed_at").asLong()).isEqualTo(markedAt);

        CLOCK.set(markedAt + 60_000);
        postEvents(array(event("mark-new", "MARK", CLOCK.millis()).put("sentence_id", sentenceId)
                .put("status", "PRACTICE")));
        sentence = getJson("/api/v1/sessions/today").path("sentences").get(0);
        assertThat(sentence.path("status").asText()).isEqualTo("PRACTICE");
        assertThat(sentence.path("status_changed_at").asLong()).isEqualTo(markedAt + 60_000);

        // 같은 시각이면 나중에 온 것이 이긴다(프론트 오버레이와 같은 규칙). 묶어서 다시 보내도 결과가 같다.
        ObjectNode tie = event("mark-tie", "MARK", CLOCK.millis()).put("sentence_id", sentenceId)
                .put("status", "MEMORIZED");
        postEvents(array(tie));
        sentence = getJson("/api/v1/sessions/today").path("sentences").get(0);
        assertThat(sentence.path("status").asText()).isEqualTo("MEMORIZED");
        assertThat(sentence.path("status_changed_at").asLong()).isEqualTo(markedAt + 60_000);

        postEvents(array(event("mark-new", "MARK", CLOCK.millis()).put("sentence_id", sentenceId)
                .put("status", "PRACTICE"), tie));
        sentence = getJson("/api/v1/sessions/today").path("sentences").get(0);
        assertThat(sentence.path("status").asText()).isEqualTo("MEMORIZED");
    }

    @Test
    @Order(6)
    void focusKeepsTheLargestSecondsAndMorningLockKeepsTheFirstDecision() throws Exception {
        long now = CLOCK.millis();
        long startedAt = now - 1_700_000;

        JsonNode res = postEvents(array(focus(startedAt, 1500, now)));
        assertThat(res.path("dashboard").path("focus").path("seconds_today").asLong()).isEqualTo(1500);
        assertThat(longs(res.path("dashboard").path("focus").path("started_ats"))).containsExactly(startedAt);

        res = postEvents(array(focus(startedAt, 1200, now)));
        assertThat(res.path("dashboard").path("focus").path("seconds_today").asLong()).isEqualTo(1500);

        res = postEvents(array(focus(startedAt, 1620, now)));
        assertThat(res.path("dashboard").path("focus").path("seconds_today").asLong()).isEqualTo(1620);
        assertThat(res.path("dashboard").path("focus").path("started_ats")).hasSize(1);

        // 같은 집중이 더 길게 다시 오면 날짜도 끝난 시각을 따른다(어제 끝난 것으로 먼저 왔다가 오늘 끝난 것으로 늘어난 경우).
        long nightStart = now - 86_400_000L - 600_000;
        res = postEvents(array(focus(nightStart, 60, now - 86_400_000L)));
        assertThat(res.path("dashboard").path("focus").path("seconds_today").asLong()).isEqualTo(1620);
        res = postEvents(array(focus(nightStart, 120, now)));
        assertThat(res.path("dashboard").path("focus").path("seconds_today").asLong()).isEqualTo(1740);

        res = postEvents(array(focus(startedAt + 1, 21_601, now), focus(startedAt + 2, 0, now)));
        assertThat(res.path("accepted")).isEmpty();
        assertThat(res.path("rejected")).hasSize(2);

        res = postEvents(array(event("lock-1", "MORNING_LOCK", now).put("result", "CLEARED")));
        assertThat(res.path("dashboard").path("morning_lock").path("state").asText()).isEqualTo("CLEARED");
        res = postEvents(array(event("lock-2", "MORNING_LOCK", now + 1).put("result", "SKIPPED")));
        assertThat(texts(res.path("accepted"))).containsExactly("lock-2");
        assertThat(res.path("dashboard").path("morning_lock").path("state").asText()).isEqualTo("CLEARED");
    }

    @Test
    @Order(7)
    void futureTimestampsAreClampedToTheReceiveTime() throws Exception {
        long sentenceId = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids")).get(1);

        JsonNode res = postEvents(array(view("from-the-future", sentenceId, "REVIEW", "AUDIO",
                CLOCK.millis() + 3 * 86_400_000L)));

        assertThat(longs(res.path("dashboard").path("review_gate").path("seen_review_ids")))
                .containsExactly(sentenceId);
        assertThat(res.path("dashboard").path("review_progress").path("seen").asInt()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void requestLevelErrorsUseTheErrorShape() throws Exception {
        ArrayNode tooMany = mapper.createArrayNode();
        for (int i = 0; i < 501; i++) {
            tooMany.add(event("e" + i, "MORNING_LOCK", CLOCK.millis()).put("result", "SKIPPED"));
        }
        JsonNode error = mapper.readTree(postRaw("/api/v1/events", eventsBody(tooMany), 400));
        assertThat(fieldNames(error)).containsExactly("error", "message");
        assertThat(error.path("error").asText()).isEqualTo("TOO_MANY_EVENTS");

        error = mapper.readTree(postRaw("/api/v1/events", "{not json", 400));
        assertThat(error.path("error").asText()).isEqualTo("INVALID_JSON");
        assertThat(error.path("message").asText()).isNotBlank();

        MockHttpServletResponse missing = mvc.perform(get("/api/v1/nope")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(mapper.readTree(missing.getContentAsString(StandardCharsets.UTF_8)).path("error").asText())
                .isEqualTo("NOT_FOUND");

        // 본문 상한: JSON 2MB, 오디오 5MB. 메모리에 올리기 전에 끊는다.
        MockHttpServletResponse hugeJson = mvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON).content(new byte[2 * 1024 * 1024 + 1]))
                .andReturn().getResponse();
        assertThat(hugeJson.getStatus()).isEqualTo(413);
        assertThat(mapper.readTree(hugeJson.getContentAsString(StandardCharsets.UTF_8)).path("error").asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
        putAudio("0123456789abcdef0123.mp3", new byte[5 * 1024 * 1024 + 1], 413);
    }

    /** 프론트의 서버 시계 추정값은 .5 로 끝날 수 있다(왕복 시간의 절반 보정). 거절하면 학습 기록이 사라진다. */
    @Test
    @Order(8)
    void fractionalClientTimestampsAreAccepted() throws Exception {
        long sentenceId = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids")).get(2);
        long now = CLOCK.millis();
        long startedAt = now - 100_000;
        String body = "{\"events\":["
                + "{\"id\":\"frac-view\",\"type\":\"VIEW\",\"occurred_at\":" + now + ".5,\"sentence_id\":" + sentenceId
                + ",\"session\":\"REVIEW\",\"via\":\"CARD\"},"
                + "{\"id\":\"focus:" + startedAt + ".5\",\"type\":\"FOCUS\",\"occurred_at\":" + now
                + ".5,\"started_at\":" + startedAt + ".5,\"seconds\":60},"
                + "{\"id\":\"frac-sentence\",\"type\":\"MARK\",\"occurred_at\":" + now + ",\"sentence_id\":"
                + sentenceId + ".5,\"status\":\"NEW\"}]}";

        JsonNode res = mapper.readTree(postRaw("/api/v1/events", body, 200));

        assertThat(texts(res.path("accepted"))).containsExactly("frac-view", "focus:" + startedAt + ".5");
        assertThat(reasonOf(res, "frac-sentence")).isEqualTo("INVALID_FIELD");
        assertThat(longs(res.path("dashboard").path("review_gate").path("seen_review_ids"))).contains(sentenceId);
        assertThat(longs(res.path("dashboard").path("focus").path("started_ats"))).contains(startedAt);
    }

    // ── 4. 다음 날: 게이트, 재분류, 스트릭 ────────────────────

    @Test
    @Order(9)
    void nextDayLocksTodayUntilYesterdayIsReviewed() throws Exception {
        List<Long> day1 = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids"));
        CLOCK.set(seoul(16, 8, 0));

        JsonNode d = getJson("/api/v1/dashboard");
        assertThat(d.path("today").asText()).isEqualTo("2026-11-16");
        assertThat(d.path("current_day").asInt()).isEqualTo(2);
        assertThat(d.path("lesson_day").asInt()).isEqualTo(2);
        assertThat(d.path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(d.path("streak").path("if_achieved_today").asInt()).isEqualTo(2);
        assertThat(d.path("streak").path("achieved_today").asBoolean()).isFalse();
        assertThat(d.path("morning_lock").path("state").asText()).isEqualTo("NONE");
        assertThat(d.path("focus").path("seconds_today").asLong()).isZero();

        JsonNode gate = d.path("review_gate");
        assertThat(gate.path("required").asBoolean()).isTrue();
        assertThat(gate.path("cleared").asBoolean()).isFalse();
        assertThat(gate.path("yesterday_day").asInt()).isEqualTo(1);
        assertThat(longs(gate.path("unlock_ids"))).containsExactlyElementsOf(day1);
        assertThat(gate.path("seen_review_ids")).isEmpty();
        assertThat(ints(d.path("review_progress").path("days"))).containsExactly(1);

        JsonNode review = getJson("/api/v1/sessions/review");
        assertThat(review.path("preview").asBoolean()).isFalse();
        assertThat(ints(review.path("days"))).containsExactly(1);

        // 롤오버 뒤에도 열어 둔 어제 큐: TODAY 로 보고해도 복습으로 기록되고 오늘 달성이 되지 않는다.
        ArrayNode stale = mapper.createArrayNode();
        for (int i = 0; i < day1.size(); i++) {
            stale.add(view("stale-" + i, day1.get(i), "TODAY", "AUDIO", CLOCK.millis()));
        }
        JsonNode res = postEvents(stale);
        assertThat(res.path("accepted")).hasSize(10);
        JsonNode after = res.path("dashboard");
        assertThat(after.path("today_progress").path("audio_seen_ids")).isEmpty();
        assertThat(after.path("streak").path("achieved_today").asBoolean()).isFalse();
        assertThat(longs(after.path("review_gate").path("seen_review_ids"))).containsExactlyElementsOf(day1);
        assertThat(after.path("review_gate").path("cleared").asBoolean()).isTrue();
        assertThat(after.path("review_progress").path("seen").asInt()).isEqualTo(10);
    }

    @Test
    @Order(10)
    void lateEventsLandOnTheDayTheyHappened() throws Exception {
        JsonNode before = getJson("/api/v1/dashboard");
        long yesterdayId = longs(before.path("review_gate").path("unlock_ids")).get(0);

        // 어제 23시에 들은 기록이 오늘 도착: 어제 날짜에 들어가고 오늘 화면은 그대로다.
        JsonNode res = postEvents(array(view("late-1", yesterdayId, "TODAY", "AUDIO", seoul(15, 23, 0))));

        assertThat(texts(res.path("accepted"))).containsExactly("late-1");
        assertThat(res.path("dashboard").path("today_progress")).isEqualTo(before.path("today_progress"));
        assertThat(res.path("dashboard").path("review_gate")).isEqualTo(before.path("review_gate"));
        assertThat(res.path("dashboard").path("streak")).isEqualTo(before.path("streak"));

        // 같은 이벤트가 (기기 시계가 크게 틀려) 오늘 시각으로 다시 와도 오늘 복습으로 두 번 기록하지 않는다.
        res = postEvents(array(view("late-1", yesterdayId, "TODAY", "AUDIO", CLOCK.millis())));
        assertThat(texts(res.path("accepted"))).containsExactly("late-1");
        assertThat(res.path("dashboard").path("review_gate")).isEqualTo(before.path("review_gate"));
    }

    @Test
    @Order(11)
    void audioRunOfDayTwoExtendsTheStreak() throws Exception {
        List<Long> day2 = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids"));
        ArrayNode events = mapper.createArrayNode();
        for (int i = 0; i < day2.size(); i++) {
            events.add(view("d2-audio-" + i, day2.get(i), "TODAY", "AUDIO", CLOCK.millis()));
        }

        JsonNode d = postEvents(events).path("dashboard");

        assertThat(d.path("streak").path("achieved_today").asBoolean()).isTrue();
        assertThat(d.path("streak").path("current").asInt()).isEqualTo(2);
        assertThat(d.path("streak").path("longest").asInt()).isEqualTo(2);
        assertThat(longs(d.path("today_progress").path("audio_seen_ids"))).containsExactlyElementsOf(day2);
    }

    // ── 5. 콘텐츠 임포트 ──────────────────────────────────────

    @Test
    @Order(12)
    void importKeepsIdsAndPastAchievements() throws Exception {
        JsonNode session = getJson("/api/v1/sessions/today");
        List<Long> idsBefore = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids"));
        int clipsBefore = getJson("/api/v1/admin/tts/status").path("clips_total").asInt();

        // Day 2 의 첫 문장을 고치고 11번째 문장을 더한다.
        ObjectNode lesson = mapper.createObjectNode().put("day", 2).put("title", session.path("title").asText());
        ArrayNode sentences = lesson.putArray("sentences");
        for (JsonNode s : session.path("sentences")) {
            sentences.addObject()
                    .put("speaker", s.path("speaker").asText())
                    .put("en_text", s.path("en_text").asText())
                    .put("ko_word_order", s.path("ko_word_order").asText())
                    .put("ko_natural", s.path("ko_natural").asText())
                    .put("core", s.path("core").asBoolean());
        }
        ((ObjectNode) sentences.get(0)).put("en_text", "What do you usually do in your free time?");
        sentences.addObject().put("speaker", "A").put("en_text", "Sounds like a plan.")
                .put("ko_word_order", "들리네요 / 계획처럼").put("ko_natural", "좋은 계획이네요.");
        ObjectNode body = mapper.createObjectNode();
        body.putArray("lessons").add(lesson);

        JsonNode result = mapper.readTree(postRaw("/api/v1/admin/lessons/import", body.toString(), 200));
        assertThat(result.path("lessons").asInt()).isEqualTo(1);
        assertThat(result.path("created").asInt()).isEqualTo(1);
        assertThat(result.path("updated").asInt()).isEqualTo(1);

        JsonNode d = getJson("/api/v1/dashboard");
        List<Long> idsAfter = longs(d.path("today_progress").path("sentence_ids"));
        assertThat(d.path("today_progress").path("total").asInt()).isEqualTo(11);
        assertThat(idsAfter.subList(0, 10)).containsExactlyElementsOf(idsBefore);
        // 완주한 순간의 기록은 콘텐츠가 바뀌어도 뒤집히지 않는다.
        assertThat(d.path("streak").path("achieved_today").asBoolean()).isTrue();
        assertThat(d.path("streak").path("current").asInt()).isEqualTo(2);

        JsonNode first = getJson("/api/v1/sessions/today").path("sentences").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(idsBefore.get(0));
        assertThat(first.path("en_text").asText()).isEqualTo("What do you usually do in your free time?");

        // 텍스트가 바뀐 문장과 새 문장만큼 새 클립이 필요해진다.
        assertThat(getJson("/api/v1/admin/tts/status").path("clips_total").asInt()).isEqualTo(clipsBefore + 1);

        // 같은 내용을 다시 넣으면 아무것도 바뀌지 않는다.
        result = mapper.readTree(postRaw("/api/v1/admin/lessons/import", body.toString(), 200));
        assertThat(result.path("created").asInt()).isZero();
        assertThat(result.path("updated").asInt()).isZero();

        JsonNode error = mapper.readTree(postRaw("/api/v1/admin/lessons/import",
                "{\"lessons\":[{\"day\":0,\"title\":\"x\",\"sentences\":[]}]}", 400));
        assertThat(error.path("error").asText()).isEqualTo("INVALID_IMPORT");
    }

    // ── 6. 오디오 업로드 ──────────────────────────────────────

    @Test
    @Order(13)
    void uploadedClipsAreValidatedIndexedAndServed() throws Exception {
        JsonNode jobs = getJson("/api/v1/admin/tts/jobs").path("jobs");
        int missing = getJson("/api/v1/admin/tts/status").path("clips_missing").asInt();
        assertThat(jobs).hasSize(missing);
        JsonNode job = jobs.get(0);
        assertThat(fieldNames(job)).containsExactly("file", "voice", "rate", "text", "sentence_ids");
        assertThat(job.path("file").asText()).matches("[0-9a-f]{20}\\.mp3");
        assertThat(job.path("voice").asText()).isEqualTo("en-US-AndrewMultilingualNeural");
        assertThat(job.path("rate").asText()).isEqualTo("-10%");
        assertThat(job.path("text").asText()).isEqualTo("Hi, I don't think we've met before.");
        long sentenceId = job.path("sentence_ids").get(0).asLong();
        String file = job.path("file").asText();
        byte[] clip = Mp3Samples.frames(100);

        JsonNode created = mapper.readTree(putAudio(file, clip, 200));
        assertThat(created.path("file").asText()).isEqualTo(file);
        assertThat(created.path("duration_ms").asLong()).isEqualTo(2400);
        assertThat(created.path("created").asBoolean()).isTrue();
        assertThat(Files.size(DATA_DIR.resolve("audio").resolve(file))).isEqualTo(clip.length);

        // 이미 유효한 클립이 있으면 덮어쓰지 않고 성공한다.
        JsonNode again = mapper.readTree(putAudio(file, Mp3Samples.frames(50), 200));
        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(again.path("duration_ms").asLong()).isEqualTo(2400);
        assertThat(Files.size(DATA_DIR.resolve("audio").resolve(file))).isEqualTo(clip.length);

        JsonNode unknown = mapper.readTree(putAudio("0123456789abcdef0123.mp3", clip, 404));
        assertThat(unknown.path("error").asText()).isEqualTo("UNKNOWN_CLIP");
        mapper.readTree(putAudio("..%2Fevil.mp3", clip, 404));

        String other = jobs.get(1).path("file").asText();
        JsonNode invalid = mapper.readTree(putAudio(other, "<html>oops</html>".getBytes(StandardCharsets.UTF_8), 422));
        assertThat(invalid.path("error").asText()).isEqualTo("INVALID_AUDIO");
        assertThat(Files.exists(DATA_DIR.resolve("audio").resolve(other))).isFalse();

        assertThat(getJson("/api/v1/admin/tts/status").path("clips_missing").asInt()).isEqualTo(missing - 1);
        assertThat(getJson("/api/v1/admin/tts/jobs").path("jobs")).hasSize(missing - 1);
        assertThat(getJson("/api/v1/admin/tts/sync").isMissingNode()).isTrue();
        assertThat(mapper.readTree(postRaw("/api/v1/admin/tts/sync", "", 200)).path("indexed").asInt()).isEqualTo(1);

        JsonNode sentence = null;
        for (JsonNode s : getJson("/api/v1/sessions/review").path("sentences")) {
            if (s.path("id").asLong() == sentenceId) {
                sentence = s;
            }
        }
        assertThat(sentence).isNotNull();
        assertThat(sentence.path("audio_url").asText()).isEqualTo("/api/v1/audio/" + file);
        assertThat(sentence.path("duration_ms").asLong()).isEqualTo(2400);
        assertThat(sentence.path("duration_estimated").asBoolean()).isFalse();

        MockHttpServletResponse served = mvc.perform(get("/api/v1/audio/" + file)).andReturn().getResponse();
        assertThat(served.getStatus()).isEqualTo(200);
        assertThat(served.getContentAsByteArray()).isEqualTo(clip);
        assertThat(served.getHeader("Cache-Control")).contains("max-age=31536000").contains("immutable");
    }

    // ── 7. 푸시 ───────────────────────────────────────────────

    @Test
    @Order(14)
    void vapidKeysAreGeneratedOnceAndSubscriptionsAreUpserted() throws Exception {
        String publicKey = getJson("/api/v1/push/vapid-public-key").path("public_key").asText();
        byte[] point = Base64.getUrlDecoder().decode(publicKey);
        assertThat(publicKey).doesNotContain("=");
        assertThat(point).hasSize(65);
        assertThat(point[0]).isEqualTo((byte) 0x04);

        JsonNode stored = mapper.readTree(DATA_DIR.resolve("vapid.json").toFile());
        assertThat(stored.path("public_key").asText()).isEqualTo(publicKey);
        assertThat(Base64.getUrlDecoder().decode(stored.path("private_key").asText())).hasSize(32);

        JsonNode sent = mapper.readTree(postRaw("/api/v1/push/test", "", 200));
        assertThat(fieldNames(sent)).containsExactly("push_sent", "webhooks_sent");
        assertThat(sent.path("push_sent").asInt()).isZero();
        assertThat(sent.path("webhooks_sent").asInt()).isZero();

        JsonNode bad = mapper.readTree(postRaw("/api/v1/push/subscribe", "{\"endpoint\":\"https://x.invalid/1\"}", 400));
        assertThat(bad.path("error").asText()).isEqualTo("INVALID_SUBSCRIPTION");

        // 키 크기가 틀린 구독(p256dh 65바이트, auth 16바이트가 아님)은 저장하지 않는다.
        String wrongKeys = "{\"endpoint\":\"https://push.example.invalid/abc\","
                + "\"keys\":{\"p256dh\":\"AAAA\",\"auth\":\"c2VjcmV0c2VjcmV0c2Vjcg\"}}";
        assertThat(mapper.readTree(postRaw("/api/v1/push/subscribe", wrongKeys, 400)).path("error").asText())
                .isEqualTo("INVALID_SUBSCRIPTION");

        String subscription = "{\"endpoint\":\"https://push.example.invalid/abc\",\"expirationTime\":null,"
                + "\"keys\":{\"p256dh\":\"" + publicKey + "\",\"auth\":\"c2VjcmV0c2VjcmV0c2Vjcg\"}}";
        postRaw("/api/v1/push/subscribe", subscription, 200);
        postRaw("/api/v1/push/subscribe", subscription, 200);
        String unsubscribe = "{\"endpoint\":\"https://push.example.invalid/abc\"}";
        assertThat(mapper.readTree(postRaw("/api/v1/push/unsubscribe", unsubscribe, 200)).path("removed").asBoolean())
                .isTrue();
        assertThat(mapper.readTree(postRaw("/api/v1/push/unsubscribe", unsubscribe, 200)).path("removed").asBoolean())
                .isFalse();
    }

    // ── 8. 시작일 변경 ────────────────────────────────────────

    @Test
    @Order(15)
    void changingTheStartDateRecomputesDayAndKeepsRecords() throws Exception {
        MockHttpServletResponse future = mvc.perform(put("/api/v1/admin/settings/start-date")
                .contentType(MediaType.APPLICATION_JSON).content("{\"start_date\":\"2026-12-01\"}"))
                .andReturn().getResponse();
        assertThat(future.getStatus()).isEqualTo(400);
        MockHttpServletResponse garbage = mvc.perform(put("/api/v1/admin/settings/start-date")
                .contentType(MediaType.APPLICATION_JSON).content("{\"start_date\":\"어제\"}"))
                .andReturn().getResponse();
        assertThat(garbage.getStatus()).isEqualTo(400);

        MockHttpServletResponse ok = mvc.perform(put("/api/v1/admin/settings/start-date")
                .contentType(MediaType.APPLICATION_JSON).content("{\"start_date\":\"2026-11-08\"}"))
                .andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(ok.getContentAsString(StandardCharsets.UTF_8)).path("start_date").asText())
                .isEqualTo("2026-11-08");

        // 오늘(11-16)은 이제 Day 9 인데 콘텐츠는 Day 5 까지뿐이다.
        JsonNode d = getJson("/api/v1/dashboard");
        assertThat(d.path("current_day").asInt()).isEqualTo(9);
        assertThat(d.path("lesson_day").asInt()).isEqualTo(5);
        assertThat(d.path("content_missing").asBoolean()).isTrue();
        assertThat(d.path("review_gate").path("yesterday_day").asInt()).isEqualTo(4);
        assertThat(ints(d.path("review_progress").path("days"))).containsExactly(4, 3, 2, 1);
        assertThat(d.path("review_progress").path("total").asInt()).isEqualTo(41);
        // 달성 기록은 날짜로 남아 있어 새 기준에서도 이틀 연속이다.
        assertThat(d.path("streak").path("current").asInt()).isEqualTo(2);
        assertThat(d.path("streak").path("achieved_today").asBoolean()).isTrue();

        JsonNode heatmap = getJson("/api/v1/habit/heatmap");
        assertThat(heatmap.path("start_date").asText()).isEqualTo("2026-11-08");
        assertThat(heatmap.path("cells").get(6).path("status").asText()).isEqualTo("MISSED");
        assertThat(heatmap.path("cells").get(7).path("status").asText()).isEqualTo("ACHIEVED");
        assertThat(heatmap.path("cells").get(8).path("status").asText()).isEqualTo("ACHIEVED");
        assertThat(heatmap.path("cells").get(9).path("status").asText()).isEqualTo("FUTURE");

        // 오늘 Day 2 를 TODAY 로 본 기록은 이제 오늘 레슨(Day 5) 밖이다. 사라지지 않고 복습으로 보인다.
        assertThat(d.path("today_progress").path("audio_seen_ids")).isEmpty();
        assertThat(d.path("review_gate").path("seen_review_ids")).hasSize(20);
    }

    // ── 9. 늦게 도착한 지난 날의 완주 ──────────────────────────

    @Test
    @Order(16)
    void lateBatchesAchieveThePastDayTheyBelongTo() throws Exception {
        // 시작일 11-08, 오늘 11-16(Day 9). Day 5 이후 콘텐츠가 없어 11-12 부터는 오늘 레슨이 Day 5 다.
        List<Long> day5 = longs(getJson("/api/v1/dashboard").path("today_progress").path("sentence_ids"));
        assertThat(day5).hasSize(10);

        // 11-14(Day 7) 낮에 카드로 9문장까지: 아직 달성이 아니다.
        ArrayNode nine = mapper.createArrayNode();
        for (int i = 0; i < 9; i++) {
            nine.add(view("late-card-" + i, day5.get(i), "TODAY", "CARD", seoul(14, 12, 0)));
        }
        postEvents(nine);
        assertThat(getJson("/api/v1/habit/heatmap").path("cells").get(6).path("status").asText())
                .isEqualTo("MISSED");

        // 마지막 한 문장이 도착하면 그날(11-14)이 소급 달성된다.
        JsonNode res = postEvents(array(view("late-card-9", day5.get(9), "TODAY", "CARD", seoul(14, 12, 5))));
        assertThat(res.path("dashboard").path("today_progress").path("card_seen_ids")).isEmpty();
        assertThat(res.path("dashboard").path("streak").path("current").asInt()).isEqualTo(3);

        // 달력으로는 11-13 새벽 02:30 이지만 03시 이전이라 11-12(Day 5) 기록이다.
        ArrayNode beforeRollover = mapper.createArrayNode();
        for (int i = 0; i < day5.size(); i++) {
            beforeRollover.add(view("night-" + i, day5.get(i), "TODAY", "AUDIO", seoul(13, 2, 30)));
        }
        JsonNode d = postEvents(beforeRollover).path("dashboard");

        JsonNode cells = getJson("/api/v1/habit/heatmap").path("cells");
        assertThat(cells.get(4).path("date").asText()).isEqualTo("2026-11-12");
        assertThat(cells.get(4).path("status").asText()).isEqualTo("ACHIEVED");
        // 11-13 은 비었지만 스트릭이 살아 있어 프리즈로 넘어간다.
        assertThat(cells.get(5).path("status").asText()).isEqualTo("FROZEN");
        assertThat(cells.get(6).path("status").asText()).isEqualTo("ACHIEVED");
        assertThat(d.path("streak").path("current").asInt()).isEqualTo(4);
        assertThat(d.path("streak").path("longest").asInt()).isEqualTo(4);
        assertThat(d.path("streak").path("freezes_left").asInt()).isEqualTo(1);
    }

    @Test
    @Order(17)
    void speakingPracticeIsRecordedApartFromTheHabit() throws Exception {
        // 앞선 테스트의 열람 기록을 지우고 시작한다(이 테스트가 마지막이다).
        jdbc.update("DELETE FROM sentence_view");
        // 오늘 레슨이 Day 5(콘텐츠 마지막)라 Day 1~5 가 나오지만(최근 먼저), 아무것도 보지 않아 모두 잠겨 있다.
        JsonNode overview = getJson("/api/v1/speak");
        assertThat(overview.path("days")).hasSize(5);
        assertThat(overview.path("days").get(0).path("day").asInt()).isEqualTo(5);
        assertThat(overview.path("days").get(0).path("done_ids")).isEmpty();
        assertThat(overview.path("days").get(0).path("sentence_ids")).hasSize(10);
        for (JsonNode d : overview.path("days")) {
            assertThat(d.path("open").asBoolean()).isFalse();
        }
        assertThat(mvc.perform(get("/api/v1/speak/days/1")).andReturn().getResponse().getStatus()).isEqualTo(404);

        // Day 1 을 9문장만 보면 아직 잠기고, 10문장을 다 보면 열린다.
        List<Long> day1Ids = longs(overview.path("days").get(4).path("sentence_ids"));
        ArrayNode views = mapper.createArrayNode();
        for (int i = 0; i < 9; i++) {
            views.add(view("speak-view-" + i, day1Ids.get(i), "REVIEW", "CARD", CLOCK.millis()));
        }
        postEvents(views);
        JsonNode partly = getJson("/api/v1/speak").path("days").get(4);
        assertThat(partly.path("open").asBoolean()).isFalse();
        assertThat(partly.path("seen").asInt()).isEqualTo(9);
        postEvents(array(view("speak-view-9", day1Ids.get(9), "REVIEW", "AUDIO", CLOCK.millis())));
        assertThat(getJson("/api/v1/speak").path("days").get(4).path("open").asBoolean()).isTrue();

        JsonNode day1 = getJson("/api/v1/speak/days/1");
        assertThat(day1.path("sentences")).hasSize(10);
        assertThat(day1.path("sentences").get(0).path("audio_url").isTextual()).isTrue();
        long first = day1.path("sentences").get(0).path("id").asLong();

        JsonNode before = getJson("/api/v1/dashboard");
        ObjectNode speak = event("speak-1", "SPEAK", CLOCK.millis()).put("sentence_id", first).put("reps", 5);
        JsonNode res = postEvents(array(speak));
        assertThat(texts(res.path("accepted"))).containsExactly("speak-1");
        postEvents(array(speak)); // 같은 이벤트를 다시 보내도 한 번만

        assertThat(longs(getJson("/api/v1/speak/days/1").path("done_ids"))).containsExactly(first);
        JsonNode day1Row = null;
        for (JsonNode d : getJson("/api/v1/speak").path("days")) {
            if (d.path("day").asInt() == 1) {
                day1Row = d;
            }
        }
        assertThat(longs(day1Row.path("done_ids"))).containsExactly(first);
        // 부가 기능이라 습관·게이트·스트릭은 그대로다.
        JsonNode after = getJson("/api/v1/dashboard");
        assertThat(after.path("today_progress")).isEqualTo(before.path("today_progress"));
        assertThat(after.path("streak")).isEqualTo(before.path("streak"));

        // 배우지 않은 Day 는 열리지 않고, 형식이 틀린 SPEAK 는 거절한다.
        MockHttpServletResponse locked = mvc.perform(get("/api/v1/speak/days/6")).andReturn().getResponse();
        assertThat(locked.getStatus()).isEqualTo(404);
        JsonNode bad = postEvents(array(event("speak-bad", "SPEAK", CLOCK.millis()).put("sentence_id", first).put("reps", 0)));
        assertThat(bad.path("accepted")).isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────

    private JsonNode getJson(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        if (response.getStatus() == 405) {
            return mapper.missingNode();
        }
        assertThat(response.getStatus()).as("GET %s", path).isEqualTo(200);
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postEvents(ArrayNode events) throws Exception {
        return mapper.readTree(postRaw("/api/v1/events", eventsBody(events), 200));
    }

    private String eventsBody(ArrayNode events) {
        ObjectNode body = mapper.createObjectNode();
        body.set("events", events);
        return body.toString();
    }

    private String postRaw(String path, String body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request = post(path);
        if (!body.isEmpty()) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("POST %s -> %s", path, content).isEqualTo(expectedStatus);
        return content;
    }

    private String putAudio(String file, byte[] bytes, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mvc.perform(put("/api/v1/admin/audio/" + file)
                .contentType("audio/mpeg").content(bytes)).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("PUT %s -> %s", file, content).isEqualTo(expectedStatus);
        return content;
    }

    private ObjectNode event(String id, String type, long occurredAt) {
        return mapper.createObjectNode().put("id", id).put("type", type).put("occurred_at", occurredAt);
    }

    private ObjectNode view(String id, long sentenceId, String session, String via, long occurredAt) {
        return event(id, "VIEW", occurredAt).put("sentence_id", sentenceId).put("session", session).put("via", via);
    }

    private ObjectNode focus(long startedAt, long seconds, long occurredAt) {
        return event("focus:" + startedAt, "FOCUS", occurredAt).put("started_at", startedAt).put("seconds", seconds);
    }

    private ArrayNode array(JsonNode... nodes) {
        ArrayNode array = mapper.createArrayNode();
        for (JsonNode node : nodes) {
            array.add(node);
        }
        return array;
    }

    private static String reasonOf(JsonNode response, String id) {
        for (JsonNode rejected : response.path("rejected")) {
            if (rejected.path("id").asText().equals(id)) {
                return rejected.path("reason").asText();
            }
        }
        return null;
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asLong()));
        return values;
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asInt()));
        return values;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 2026년 11월 {@code day}일 {@code hour}:{@code minute} (Asia/Seoul) 의 epoch ms. */
    private static long seoul(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(SEOUL).toInstant().toEpochMilli();
    }

    /** 테스트가 옮길 수 있는 시계. */
    static final class MutableClock extends Clock {

        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void set(long millis) {
            this.millis = millis;
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
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
