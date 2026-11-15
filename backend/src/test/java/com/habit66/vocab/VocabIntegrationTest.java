package com.habit66.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habit66.vocab.TermStore.Example;

/**
 * 단어장(SPEC F-08, 4.4, 4.9)을 실제 SQLite·시드 위에서 확인한다. 생성기는 가짜로 바꿔 끼운다.
 * 앞 단계의 상태를 이어 쓰므로 순서대로 실행한다.
 */
@SpringBootTest(properties = {
        "app.data-dir=build/test-data/vocab-it",
        "app.tts.auto-generate=false",
        "app.reminder.enabled=false",
        "app.vocab.generator=none",
        "app.admin-token=",
        "app.push.public-key=",
        "app.push.private-key="
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VocabIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final MutableClock CLOCK = new MutableClock(seoul(15, 10, 0));
    private static final FakeGenerator GENERATOR = new FakeGenerator();

    static {
        // 매번 빈 DB 에서 시작한다(시드가 다시 들어간다).
        Path dir = Paths.get("build", "test-data", "vocab-it");
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        TermGenerator fakeGenerator() {
            return GENERATOR;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TermGenerationQueue queue;

    // ── 세션 문장의 terms ──────────────────────────────────────

    @Test
    @Order(1)
    void sessionSentencesCarryPrebuiltTerms() throws Exception {
        JsonNode first = getJson("/api/v1/sessions/today").path("sentences").get(0);
        JsonNode terms = first.path("terms");
        assertThat(terms).hasSize(2);
        assertThat(fieldNames(terms.get(0))).containsExactly("term_key", "text", "form", "meaning");
        assertThat(terms.get(0).path("term_key").asText()).isEqualTo("i don't think we've met");
        assertThat(terms.get(0).path("text").asText()).isEqualTo("I don't think we've met");
        assertThat(terms.get(0).path("form").asText()).isEqualTo("I don't think we've met");
        assertThat(terms.get(0).path("meaning").asText()).isNotBlank();
        assertThat(terms.get(1).path("term_key").asText()).isEqualTo("before");
        assertThat(getJson("/api/v1/vocab").path("entries")).isEmpty();
    }

    // ── 담기 ──────────────────────────────────────────────────

    @Test
    @Order(2)
    void addingAPrebuiltTermIsReadyAtOnceWithExampleAudioJobs() throws Exception {
        long sentenceId = firstSentenceId();
        JsonNode response = postEvents(array(add("a1", "I don't think we've met", sentenceId, CLOCK.millis())));
        assertThat(texts(response.path("accepted"))).containsExactly("a1");

        JsonNode entries = getJson("/api/v1/vocab").path("entries");
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(fieldNames(entry)).containsExactly("term_key", "text", "meaning", "nuance", "examples", "status",
                "source", "note", "mastered", "added_at", "sentence");
        assertThat(entry.path("term_key").asText()).isEqualTo("i don't think we've met");
        assertThat(entry.path("status").asText()).isEqualTo("READY");
        assertThat(entry.path("source").asText()).isEqualTo("CONTENT");
        assertThat(entry.path("nuance").asText()).isNotBlank();
        assertThat(entry.path("note").isNull()).isTrue();
        assertThat(entry.path("mastered").asBoolean()).isFalse();
        assertThat(entry.path("added_at").asLong()).isEqualTo(CLOCK.millis());
        assertThat(entry.path("sentence").path("id").asLong()).isEqualTo(sentenceId);
        assertThat(entry.path("sentence").path("day").asInt()).isEqualTo(1);
        assertThat(fieldNames(entry.path("sentence"))).containsExactly("id", "day", "en_text", "ko_natural");

        JsonNode examples = entry.path("examples");
        assertThat(examples).hasSize(3);
        assertThat(fieldNames(examples.get(0))).containsExactly("en", "ko", "audio_url", "duration_ms",
                "duration_estimated");
        assertThat(examples.get(0).path("audio_url").isNull()).isTrue();
        assertThat(examples.get(0).path("duration_estimated").asBoolean()).isTrue();

        // 담긴 표현의 예문 3개가 음성 작업 목록에 들어간다: 1·3번 Ava, 2번 Andrew.
        List<String> voices = new ArrayList<>();
        for (JsonNode example : examples) {
            JsonNode job = jobFor(example.path("en").asText());
            assertThat(job).as("job for %s", example.path("en").asText()).isNotNull();
            assertThat(job.path("sentence_ids")).isEmpty();
            voices.add(job.path("voice").asText());
        }
        assertThat(voices).containsExactly("en-US-AvaMultilingualNeural", "en-US-AndrewMultilingualNeural",
                "en-US-AvaMultilingualNeural");
    }

    @Test
    @Order(3)
    void addingTheSameTermAgainChangesNothing() throws Exception {
        long before = getJson("/api/v1/vocab").path("entries").get(0).path("added_at").asLong();
        CLOCK.set(CLOCK.millis() + 60_000);
        JsonNode response = postEvents(array(add("a1", "I don't think we've met", firstSentenceId(), CLOCK.millis()),
                add("a1b", "  I DON'T think   we've met ", null, CLOCK.millis())));
        assertThat(texts(response.path("accepted"))).containsExactly("a1", "a1b");
        JsonNode entries = getJson("/api/v1/vocab").path("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("added_at").asLong()).isEqualTo(before);
    }

    @Test
    @Order(4)
    void unknownWordIsGeneratedInTheBackground() throws Exception {
        CLOCK.set(CLOCK.millis() + 60_000);
        long sentenceId = firstSentenceId();
        postEvents(array(add("a2", "Grab", sentenceId, CLOCK.millis())));

        JsonNode entry = await("grab", "READY");
        assertThat(entry.path("text").asText()).isEqualTo("Grab");
        assertThat(entry.path("source").asText()).isEqualTo("GENERATED");
        assertThat(entry.path("meaning").asText()).isEqualTo("뜻:Grab");
        assertThat(entry.path("examples")).hasSize(3);
        assertThat(GENERATOR.requests).anySatisfy(request -> {
            assertThat(request.text()).isEqualTo("Grab");
            assertThat(request.contextSentence()).isEqualTo("Hi, I don't think we've met before.");
        });
        // 최근에 담은 것이 먼저.
        assertThat(getJson("/api/v1/vocab").path("entries").get(0).path("term_key").asText()).isEqualTo("grab");
    }

    @Test
    @Order(5)
    void manualMeaningIsSavedWithoutGenerating() throws Exception {
        CLOCK.set(CLOCK.millis() + 60_000);
        int calls = GENERATOR.requests.size();
        JsonNode response = postEvents(array(add("a3", "nifty", 999_999L, CLOCK.millis())
                .put("meaning", " 멋진, 쓸모 있는 ").put("note", "친구가 쓴 말")));
        assertThat(texts(response.path("accepted"))).containsExactly("a3");
        JsonNode entry = entry("nifty");
        assertThat(entry.path("status").asText()).isEqualTo("READY");
        assertThat(entry.path("source").asText()).isEqualTo("MANUAL");
        assertThat(entry.path("meaning").asText()).isEqualTo("멋진, 쓸모 있는");
        assertThat(entry.path("note").asText()).isEqualTo("친구가 쓴 말");
        assertThat(entry.path("examples")).isEmpty();
        // 현재 콘텐츠에 없는 문장은 연결하지 않는다(담기 자체는 받는다).
        assertThat(entry.path("sentence").isNull()).isTrue();
        Thread.sleep(200);
        assertThat(GENERATOR.requests).hasSize(calls);
    }

    // ── 실패와 재시도 ──────────────────────────────────────────

    @Test
    @Order(6)
    void failedGenerationIsRetriedUpToThreeTimesADay() throws Exception {
        CLOCK.set(CLOCK.millis() + 60_000);
        postEvents(array(add("a4", "zzfail", null, CLOCK.millis())));
        await("zzfail", "FAILED");
        assertThat(attemptsFor("zzfail")).isEqualTo(1);

        retry(1);
        await("zzfail", "FAILED");
        retry(1);
        await("zzfail", "FAILED");
        assertThat(attemptsFor("zzfail")).isEqualTo(3);

        // 오늘 몫을 다 썼으면 시도하지 않고 FAILED 로 둔다.
        retry(1);
        await("zzfail", "FAILED");
        assertThat(attemptsFor("zzfail")).isEqualTo(3);

        // 다음 논리 날짜(03시 롤오버 뒤)에는 다시 시도한다.
        CLOCK.set(seoul(16, 4, 0));
        retry(1);
        await("zzfail", "FAILED");
        assertThat(attemptsFor("zzfail")).isEqualTo(4);
    }

    @Test
    @Order(7)
    void loginProblemStopsTheQueuedItemsButNotLaterOnes() throws Exception {
        GENERATOR.loginExpired = true;
        CLOCK.set(CLOCK.millis() + 60_000);
        postEvents(array(add("a5", "fetch", null, CLOCK.millis())));
        await("fetch", "FAILED");
        GENERATOR.loginExpired = false;

        // 다시 맡기면(로그인한 뒤) 새로 시도한다. zzfail 도 같이 다시 맡겨진다.
        retry(2);
        assertThat(await("fetch", "READY").path("meaning").asText()).isEqualTo("뜻:fetch");
    }

    // ── 외움·수정·빼기 ────────────────────────────────────────

    @Test
    @Order(8)
    void masterFollowsTheLatestOccurredAt() throws Exception {
        long t = CLOCK.millis();
        postEvents(array(master("m1", "grab", true, t)));
        assertThat(entry("grab").path("mastered").asBoolean()).isTrue();
        // 늦게 도착한 옛 이벤트는 지지 않는다.
        postEvents(array(master("m0", "grab", false, t - 5_000)));
        assertThat(entry("grab").path("mastered").asBoolean()).isTrue();
        postEvents(array(master("m2", "grab", false, t)));
        assertThat(entry("grab").path("mastered").asBoolean()).isFalse();
        postEvents(array(master("m3", "grab", true, t + 1_000)));
        assertThat(entry("grab").path("mastered").asBoolean()).isTrue();
    }

    @Test
    @Order(9)
    void editSetsManualMeaningAndNote() throws Exception {
        postEvents(array(event("e1", "VOCAB_EDIT", CLOCK.millis()).put("term_key", "before")));
        assertThat(entryOrNull("before")).as("not in vocab, edit ignored").isNull();

        postEvents(array(event("e2", "VOCAB_EDIT", CLOCK.millis()).put("term_key", "grab")
                .put("meaning", "잡다, 간단히 먹다").put("note", "grab a bite")));
        JsonNode grab = entry("grab");
        assertThat(grab.path("meaning").asText()).isEqualTo("잡다, 간단히 먹다");
        assertThat(grab.path("source").asText()).isEqualTo("MANUAL");
        assertThat(grab.path("note").asText()).isEqualTo("grab a bite");
        assertThat(grab.path("examples")).hasSize(3);

        // 뜻 없이 메모만 지우면 뜻은 그대로다.
        postEvents(array(event("e3", "VOCAB_EDIT", CLOCK.millis()).put("term_key", "grab").putNull("meaning")
                .putNull("note")));
        assertThat(entry("grab").path("meaning").asText()).isEqualTo("잡다, 간단히 먹다");
        assertThat(entry("grab").path("note").isNull()).isTrue();
    }

    @Test
    @Order(10)
    void removeHidesTheEntryAndReAddStartsFresh() throws Exception {
        postEvents(array(event("r1", "VOCAB_REMOVE", CLOCK.millis()).put("term_key", "grab")));
        assertThat(entryOrNull("grab")).isNull();
        // 표현은 남아 있어 다시 담으면 만든 내용이 그대로 온다.
        CLOCK.set(CLOCK.millis() + 60_000);
        int calls = GENERATOR.requests.size();
        postEvents(array(add("a6", "grab", null, CLOCK.millis())));
        JsonNode grab = entry("grab");
        assertThat(grab.path("meaning").asText()).isEqualTo("잡다, 간단히 먹다");
        assertThat(grab.path("mastered").asBoolean()).isFalse();
        assertThat(grab.path("added_at").asLong()).isEqualTo(CLOCK.millis());
        assertThat(GENERATOR.requests).hasSize(calls);
    }

    @Test
    @Order(11)
    void malformedVocabEventsAreRejectedOneByOne() throws Exception {
        JsonNode response = postEvents(array(
                add("bad1", "   ", null, CLOCK.millis()),
                event("bad2", "VOCAB_MASTER", CLOCK.millis()).put("term_key", "grab"),
                event("bad3", "VOCAB_REMOVE", CLOCK.millis()),
                add("bad4", "x".repeat(81), null, CLOCK.millis()),
                add("bad5", "ok", null, CLOCK.millis()).put("meaning", 3),
                event("ok1", "VOCAB_REMOVE", CLOCK.millis()).put("term_key", "never-added")));
        assertThat(texts(response.path("accepted"))).containsExactly("ok1");
        for (String id : List.of("bad1", "bad2", "bad3", "bad4", "bad5")) {
            assertThat(reasonOf(response, id)).as(id).isEqualTo("INVALID_FIELD");
        }
    }

    @Test
    @Order(12)
    void resentOldEventsDoNotUndoLaterOnes() throws Exception {
        long t = CLOCK.millis();
        postEvents(array(add("x1", "resurrect", null, t), event("x2", "VOCAB_REMOVE", t + 1).put("term_key", "resurrect")));
        assertThat(entryOrNull("resurrect")).isNull();
        // 응답이 유실돼 옛 담기가 다시 와도 뺀 표현이 되살아나지 않는다.
        JsonNode response = postEvents(array(add("x1", "resurrect", null, t)));
        assertThat(texts(response.path("accepted"))).containsExactly("x1");
        assertThat(entryOrNull("resurrect")).isNull();
    }

    @Test
    @Order(13)
    void importFinishingDuringGenerationWins() throws Exception {
        GENERATOR.gate = new CountDownLatch(1);
        CLOCK.set(CLOCK.millis() + 60_000);
        postEvents(array(add("x3", "latchword", null, CLOCK.millis())));
        assertThat(GENERATOR.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 생성이 도는 사이 같은 표현이 완성된 콘텐츠로 들어온다.
        String lesson = """
                {"lessons":[{"day":6,"title":"t","sentences":[{"speaker":"A","en_text":"I like latchword a lot.",
                 "ko_word_order":"나는 좋아해 / 래치워드를 / 많이","ko_natural":"래치워드 정말 좋아.",
                 "terms":[{"text":"latchword","meaning":"콘텐츠 뜻","nuance":"콘텐츠 느낌",
                   "examples":[{"en":"One latchword.","ko":"하나"},{"en":"Two latchword.","ko":"둘"},
                               {"en":"Three latchword.","ko":"셋"}]}]}]}]}""";
        postRaw("/api/v1/admin/lessons/import", lesson);
        GENERATOR.gate.countDown();

        await("latchword", "READY");
        // 생성 결과의 저장(또는 무시)까지 끝난 뒤의 최종 값을 본다.
        JsonNode entry = entry("latchword");
        assertThat(entry.path("source").asText()).isEqualTo("CONTENT");
        assertThat(entry.path("meaning").asText()).isEqualTo("콘텐츠 뜻");
        assertThat(entry.path("examples").get(0).path("en").asText()).isEqualTo("One latchword.");
        GENERATOR.gate = null;
    }

    // ── 도우미 ────────────────────────────────────────────────

    private JsonNode await(String termKey, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode entry = null;
        while (System.currentTimeMillis() < deadline) {
            entry = entryOrNull(termKey);
            if (entry != null && status.equals(entry.path("status").asText())
                    && GENERATOR.idle() && queue.idle()) {
                return entry;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("term " + termKey + " did not reach " + status + ": " + entry);
    }

    private int attemptsFor(String text) {
        return (int) GENERATOR.requests.stream().filter(r -> r.text().equals(text)).count();
    }

    private void retry(int expectedQueued) throws Exception {
        JsonNode response = mapper.readTree(postRaw("/api/v1/admin/vocab/retry", "{}"));
        assertThat(response.path("queued").asInt()).isEqualTo(expectedQueued);
        Thread.sleep(50);
    }

    private JsonNode entry(String termKey) throws Exception {
        JsonNode entry = entryOrNull(termKey);
        assertThat(entry).as("entry %s", termKey).isNotNull();
        return entry;
    }

    private JsonNode entryOrNull(String termKey) throws Exception {
        for (JsonNode entry : getJson("/api/v1/vocab").path("entries")) {
            if (entry.path("term_key").asText().equals(termKey)) {
                return entry;
            }
        }
        return null;
    }

    private JsonNode jobFor(String text) throws Exception {
        for (JsonNode job : getJson("/api/v1/admin/tts/jobs").path("jobs")) {
            if (job.path("text").asText().equals(text)) {
                return job;
            }
        }
        return null;
    }

    private long firstSentenceId() throws Exception {
        return getJson("/api/v1/sessions/today").path("sentences").get(0).path("id").asLong();
    }

    private JsonNode getJson(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).as("GET %s", path).isEqualTo(200);
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postEvents(ArrayNode events) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.set("events", events);
        return mapper.readTree(postRaw("/api/v1/events", body.toString()));
    }

    private String postRaw(String path, String body) throws Exception {
        MockHttpServletResponse response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("POST %s -> %s", path, content).isEqualTo(200);
        return content;
    }

    private ObjectNode event(String id, String type, long occurredAt) {
        return mapper.createObjectNode().put("id", id).put("type", type).put("occurred_at", occurredAt);
    }

    private ObjectNode add(String id, String text, Long sentenceId, long occurredAt) {
        ObjectNode node = event(id, "VOCAB_ADD", occurredAt).put("text", text);
        if (sentenceId == null) {
            node.putNull("sentence_id");
        } else {
            node.put("sentence_id", sentenceId);
        }
        return node.putNull("meaning").putNull("note");
    }

    private ObjectNode master(String id, String termKey, boolean mastered, long occurredAt) {
        return event(id, "VOCAB_MASTER", occurredAt).put("term_key", termKey).put("mastered", mastered);
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

    private static long seoul(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(SEOUL).toInstant().toEpochMilli();
    }

    /** "zz"로 시작하는 표현은 실패하고, loginExpired 동안은 로그인 만료로 실패한다. */
    static final class FakeGenerator implements TermGenerator {

        final Queue<Request> requests = new ConcurrentLinkedQueue<>();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean loginExpired;
        /** 있으면 풀릴 때까지 생성을 붙잡아 둔다. */
        volatile CountDownLatch gate;
        private volatile int running;

        @Override
        public Generated generate(Request request) throws GenerationException {
            running++;
            try {
                requests.add(request);
                CountDownLatch hold = gate;
                if (hold != null) {
                    entered.countDown();
                    try {
                        hold.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (loginExpired) {
                    throw new GenerationException("OAuth session expired", ClaudeCliGenerator.LOGIN_HINT);
                }
                if (request.text().startsWith("zz")) {
                    throw new GenerationException("fake failure");
                }
                return new Generated("뜻:" + request.text(), "느낌:" + request.text(), List.of(
                        new Example("Ex one " + request.text() + ".", "예문 하나"),
                        new Example("Ex two " + request.text() + ".", "예문 둘"),
                        new Example("Ex three " + request.text() + ".", "예문 셋")));
            } finally {
                running--;
            }
        }

        boolean idle() {
            return running == 0;
        }
    }

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
