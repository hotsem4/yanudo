package com.habit66.vocab;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.WriteLock;

/**
 * term 표의 유일한 쓰기 소유자. 표현의 출처(source)와 상태(status)가 바뀌는 규칙이 전부 여기 있다.
 *
 * <ul>
 * <li>사용자가 직접 적은 뜻({@code MANUAL})은 임포트도 생성기도 덮지 않는다. 비어 있는 느낌·예문만 채운다.</li>
 * <li>{@code CONTENT}·{@code GENERATED} 내용은 최신 임포트가 덮는다.</li>
 * </ul>
 */
@Repository
public class TermStore {

    public static final String CONTENT = "CONTENT";
    public static final String GENERATED = "GENERATED";
    public static final String MANUAL = "MANUAL";

    public static final String READY = "READY";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";

    public record Example(String en, String ko) {
    }

    /** @param examples 아직 없으면 빈 목록 */
    public record Term(long id, String termKey, String text, String meaning, String nuance, List<Example> examples,
            String source, String status, int attempts, long updatedAt) {
    }

    /** 세션 응답의 가벼운 모양(뜻까지만). */
    public record SentenceTerm(String termKey, String text, String form, String meaning) {
    }

    /** 단어장에 담긴 표현 하나. @param sentenceId 담을 때 보고 있던 문장(없을 수 있다) */
    public record Entry(Term term, Long sentenceId, String note, boolean mastered, long addedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(TermStore.class);
    private static final TypeReference<List<Example>> EXAMPLES = new TypeReference<>() {
    };
    private static final String COLUMNS =
            "id, term_key, text, meaning, nuance, examples_json, source, status, attempts, updated_at";

    private final JdbcTemplate jdbc;
    private final WriteLock writes;
    private final ObjectMapper mapper;

    public TermStore(JdbcTemplate jdbc, WriteLock writes, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.mapper = mapper;
    }

    // ── 읽기 ──────────────────────────────────────────────────

    public Optional<Term> findByKey(String termKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM term WHERE term_key = ?", this::toTerm, termKey)
                .stream().findFirst();
    }

    public Optional<Term> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM term WHERE id = ?", this::toTerm, id).stream().findFirst();
    }

    /** 단어장에 담긴 표현 전부, 최근에 담은 것이 먼저. */
    public List<Entry> entries() {
        return jdbc.query("""
                SELECT t.id, t.term_key, t.text, t.meaning, t.nuance, t.examples_json, t.source, t.status,
                       t.attempts, t.updated_at, v.sentence_id, v.note, v.mastered, v.added_at
                FROM vocab_entry v JOIN term t ON t.id = v.term_id
                ORDER BY v.added_at DESC, v.term_id DESC
                """, (rs, row) -> {
                    long sentenceId = rs.getLong("sentence_id");
                    Long sentence = rs.wasNull() ? null : sentenceId;
                    return new Entry(toTerm(rs, row), sentence, rs.getString("note"), rs.getBoolean("mastered"),
                            rs.getLong("added_at"));
                });
    }

    /** 단어장에 담겨 있고 아직 만들어지지 않은(PENDING·FAILED) 생성 대상 표현. 직접 적은 표현(MANUAL)은 빼고. */
    public List<Long> generationTargets() {
        return jdbc.queryForList("""
                SELECT t.id FROM term t JOIN vocab_entry v ON v.term_id = t.id
                WHERE t.status IN ('PENDING', 'FAILED') AND t.source <> 'MANUAL'
                ORDER BY v.added_at
                """, Long.class);
    }

    /** 문장마다 미리 만든 표현(임포트한 순서). 표현이 없는 문장은 맵에 없다. */
    public Map<Long, List<SentenceTerm>> termsOf(Collection<Long> sentenceIds) {
        Map<Long, List<SentenceTerm>> bySentence = new HashMap<>();
        if (sentenceIds.isEmpty()) {
            return bySentence;
        }
        String marks = String.join(",", sentenceIds.stream().map(id -> "?").toList());
        RowCallbackHandler collect = rs -> bySentence.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                .add(new SentenceTerm(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        // 한 문장의 표현은 임포트 때 통째로 지우고 순서대로 넣으므로 rowid 순서가 JSON 순서다.
        jdbc.query("""
                SELECT st.sentence_id, t.term_key, t.text, st.form, t.meaning
                FROM sentence_term st JOIN term t ON t.id = st.term_id
                WHERE st.sentence_id IN (%s)
                ORDER BY st.sentence_id, st.rowid
                """.formatted(marks), collect, sentenceIds.toArray());
        return bySentence;
    }

    // ── 쓰기 ──────────────────────────────────────────────────

    /** 콘텐츠 임포트의 유일한 입구. 표현 id 를 돌려준다. */
    public long upsertFromContent(String text, String meaning, String nuance, List<Example> examples, long now) {
        String shown = TermKey.clean(text);
        String key = TermKey.of(text);
        String json = toJson(examples);
        return writes.call(() -> {
            Term existing = findByKey(key).orElse(null);
            if (existing == null) {
                return insert(key, shown, meaning, nuance, json, CONTENT, READY, now);
            }
            if (MANUAL.equals(existing.source())) {
                jdbc.update("""
                        UPDATE term SET nuance = COALESCE(nuance, ?), examples_json = COALESCE(examples_json, ?),
                               status = 'READY', updated_at = ?
                        WHERE id = ?
                        """, nuance, json, now, existing.id());
            } else {
                jdbc.update("""
                        UPDATE term SET text = ?, meaning = ?, nuance = ?, examples_json = ?,
                               source = 'CONTENT', status = 'READY', updated_at = ?
                        WHERE id = ?
                        """, shown, meaning, nuance, json, now, existing.id());
            }
            return existing.id();
        });
    }

    /** 미리 만들지 않은 표현을 처음 담을 때: 생성기를 기다리는 빈 표현. */
    public long insertPending(String text, long now) {
        return writes.call(() -> insert(TermKey.of(text), TermKey.clean(text), null, null, null, GENERATED, PENDING,
                now));
    }

    /** 사용자가 뜻을 직접 적어 담을 때. 생성하지 않는다. */
    public long insertManual(String text, String meaning, long now) {
        return writes.call(() -> insert(TermKey.of(text), TermKey.clean(text), meaning, null, null, MANUAL, READY,
                now));
    }

    /** 사용자가 직접 고친 뜻. 이후 임포트·생성기가 덮지 않는다. */
    public void setManualMeaning(long id, String meaning, long now) {
        writes.run(() -> jdbc.update("""
                UPDATE term SET meaning = ?, source = 'MANUAL', status = 'READY', updated_at = ? WHERE id = ?
                """, meaning, now, id));
    }

    /**
     * 생성을 다시 맡긴다. updated_at 은 바꾸지 않는다: 아직 만들어지지 않은 표현의 updated_at 은 "마지막으로 생성을
     * 시도한 시각"이고, 그 날짜로 하루 시도 횟수를 센다. 여기서 바꾸면 어제의 횟수가 오늘 것으로 읽힌다.
     */
    public void markPending(long id) {
        writes.run(() -> jdbc.update("""
                UPDATE term SET status = 'PENDING'
                WHERE id = ? AND source <> 'MANUAL' AND status IN ('PENDING', 'FAILED')
                """, id));
    }

    /** 생성 시도 한 번을 센다. */
    public void countAttempt(long id, int attempts, long now) {
        writes.run(() -> jdbc.update("UPDATE term SET attempts = ?, updated_at = ? WHERE id = ?", attempts, now, id));
    }

    /**
     * 생성 결과를 저장한다. 그 사이 사용자가 뜻을 직접 적었으면(MANUAL) 뜻은 그대로 두고 비어 있는 느낌·예문만 채운다.
     * 생성 중에 임포트가 같은 표현을 완성된 콘텐츠로 바꿨으면(더 이상 PENDING 이 아니면) 아무것도 하지 않는다.
     */
    public void saveGenerated(long id, String meaning, String nuance, List<Example> examples, long now) {
        String json = toJson(examples);
        writes.run(() -> jdbc.update("""
                UPDATE term SET
                    meaning = CASE WHEN source = 'MANUAL' THEN meaning ELSE ? END,
                    nuance = CASE WHEN source = 'MANUAL' THEN COALESCE(nuance, ?) ELSE ? END,
                    examples_json = CASE WHEN source = 'MANUAL' THEN COALESCE(examples_json, ?) ELSE ? END,
                    source = CASE WHEN source = 'MANUAL' THEN source ELSE 'GENERATED' END,
                    status = 'READY', updated_at = ?
                WHERE id = ? AND (status = 'PENDING' OR source = 'MANUAL')
                """, meaning, nuance, nuance, json, json, now, id));
    }

    /**
     * 생성 실패. 아직 PENDING 인 표현만 FAILED 로 바꾼다(그사이 임포트·직접 입력으로 READY 가 됐으면 그대로 둔다).
     * updated_at 은 바꾸지 않는다: 이 값은 "마지막으로 시도를 시작한 시각"이고, 롤오버 직전에 시작해 직후에 실패한
     * 시도가 다음 날 횟수로 세어지면 안 된다.
     */
    public void markFailed(long id) {
        writes.run(() -> jdbc.update("UPDATE term SET status = 'FAILED' WHERE id = ? AND status = 'PENDING'", id));
    }

    private long insert(String key, String text, String meaning, String nuance, String examplesJson, String source,
            String status, long now) {
        jdbc.update("""
                INSERT INTO term (term_key, text, meaning, nuance, examples_json, source, status, attempts,
                                  created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, key, text, meaning, nuance, examplesJson, source, status, now, now);
        return jdbc.queryForObject("SELECT id FROM term WHERE term_key = ?", Long.class, key);
    }

    private Term toTerm(ResultSet rs, int row) throws SQLException {
        return new Term(rs.getLong("id"), rs.getString("term_key"), rs.getString("text"), rs.getString("meaning"),
                rs.getString("nuance"), fromJson(rs.getString("examples_json")), rs.getString("source"),
                rs.getString("status"), rs.getInt("attempts"), rs.getLong("updated_at"));
    }

    private String toJson(List<Example> examples) {
        if (examples == null || examples.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(examples);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize examples", e);
        }
    }

    private List<Example> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, EXAMPLES);
        } catch (JsonProcessingException e) {
            log.warn("Ignoring unreadable examples_json: {}", e.getOriginalMessage());
            return List.of();
        }
    }
}
