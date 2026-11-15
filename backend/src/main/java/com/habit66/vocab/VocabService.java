package com.habit66.vocab;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.habit66.content.Catalog;
import com.habit66.content.CatalogService;
import com.habit66.tts.AudioLibrary;
import com.habit66.tts.TtsService;
import com.habit66.tts.TtsService.Clip;
import com.habit66.vocab.TermStore.Entry;
import com.habit66.vocab.TermStore.Example;
import com.habit66.vocab.TermStore.Term;

/**
 * 단어장(SPEC F-08). vocab_entry 의 유일한 쓰기 소유자이고, 표현(term) 내용은 {@link TermStore}를 거쳐 쓴다.
 * 쓰기는 {@code POST /events}의 VOCAB_* 이벤트로만 들어온다(EventService 의 트랜잭션 안). 같은 이벤트를 다시 보내도
 * 결과가 같다.
 */
@Service
public class VocabService {

    public record ExampleView(String en, String ko, String audioUrl, long durationMs, boolean durationEstimated) {
    }

    public record SentenceRef(long id, int day, String enText, String koNatural) {
    }

    public record EntryView(String termKey, String text, String meaning, String nuance, List<ExampleView> examples,
            String status, String source, String note, boolean mastered, long addedAt, SentenceRef sentence) {
    }

    public record VocabList(List<EntryView> entries) {
    }

    private final JdbcTemplate jdbc;
    private final TermStore terms;
    private final TermGenerationQueue generation;
    private final VocabClips clips;
    private final AudioLibrary library;
    private final TtsService tts;
    private final CatalogService catalog;

    public VocabService(JdbcTemplate jdbc, TermStore terms, TermGenerationQueue generation, VocabClips clips,
            AudioLibrary library, TtsService tts, CatalogService catalog) {
        this.jdbc = jdbc;
        this.terms = terms;
        this.generation = generation;
        this.clips = clips;
        this.library = library;
        this.tts = tts;
        this.catalog = catalog;
    }

    // ── 읽기 ──────────────────────────────────────────────────

    /** 최근에 담은 것이 먼저. */
    public VocabList list() {
        Catalog cat = catalog.current();
        List<EntryView> views = new ArrayList<>();
        for (Entry entry : terms.entries()) {
            Term term = entry.term();
            List<ExampleView> examples = new ArrayList<>();
            for (int i = 0; i < term.examples().size(); i++) {
                Example example = term.examples().get(i);
                Clip clip = library.clip(clips.utterance(i, example.en()));
                examples.add(new ExampleView(example.en(), example.ko(), clip.audioUrl(), clip.durationMs(),
                        clip.durationEstimated()));
            }
            SentenceRef sentence = entry.sentenceId() == null ? null
                    : cat.sentence(entry.sentenceId())
                            .map(s -> new SentenceRef(s.id(), s.day(), s.enText(), s.koNatural())).orElse(null);
            views.add(new EntryView(term.termKey(), term.text(), term.meaning(), term.nuance(), examples,
                    term.status(), term.source(), entry.note(), entry.mastered(), entry.addedAt(), sentence));
        }
        return new VocabList(views);
    }

    // ── 쓰기 (EventService 의 트랜잭션·쓰기 잠금 안에서 불린다) ─────────

    /**
     * 이 이벤트 id 를 처음 받았으면 기록하고 true. 이미 처리한 id 면 false(적용하지 않는다).
     * 담기·빼기는 순서에 따라 결과가 달라서, 응답이 유실돼 옛 이벤트가 다시 와도 뒤의 결과를 되돌리지 않게 한다.
     */
    public boolean claim(String eventId, long now) {
        return jdbc.update("INSERT OR IGNORE INTO vocab_event (id, applied_at) VALUES (?, ?)", eventId, now) > 0;
    }

    /**
     * 담는다. 이미 담겨 있으면 아무것도 하지 않는다. 뜻을 직접 주면 그 뜻으로 저장하고 생성하지 않는다.
     *
     * @param sentenceId 담을 때 보고 있던 문장. 현재 콘텐츠에 없으면 연결하지 않는다
     */
    public void add(String text, Long sentenceId, String meaning, String note, long occurredAt, long now) {
        String key = TermKey.of(text);
        Term term = terms.findByKey(key).orElse(null);
        if (term != null && contains(term.id())) {
            return;
        }
        long termId;
        if (term == null) {
            termId = meaning != null ? terms.insertManual(text, meaning, now) : terms.insertPending(text, now);
        } else {
            termId = term.id();
            if (meaning != null) {
                terms.setManualMeaning(termId, meaning, now);
            } else if (TermStore.FAILED.equals(term.status())) {
                terms.markPending(termId);
            }
        }
        Long linked = sentenceId != null && catalog.current().sentence(sentenceId).isPresent() ? sentenceId : null;
        jdbc.update("""
                INSERT OR IGNORE INTO vocab_entry (term_id, sentence_id, note, mastered, mastered_at, added_at)
                VALUES (?, ?, ?, 0, NULL, ?)
                """, termId, linked, note, occurredAt);
        boolean needsGeneration = terms.findById(termId)
                .map(t -> TermStore.PENDING.equals(t.status()) && !TermStore.MANUAL.equals(t.source()))
                .orElse(false);
        afterCommit(() -> {
            if (needsGeneration) {
                generation.enqueue(termId);
            } else if (tts.autoGenerate()) {
                // 미리 만든 표현은 예문이 이미 있으니 바로 음성을 만든다.
                tts.generate();
            }
        });
    }

    /** 단어장에서 뺀다. 표현 자체는 남는다(다른 문장의 단어 고르기에 쓰인다). */
    public void remove(String termKey) {
        jdbc.update("DELETE FROM vocab_entry WHERE term_id = (SELECT id FROM term WHERE term_key = ?)", termKey);
    }

    /** 외웠어요 표시. 같은 시각이면 나중에 온 것이 이긴다. */
    public void master(String termKey, boolean mastered, long occurredAt) {
        jdbc.update("""
                UPDATE vocab_entry SET mastered = ?, mastered_at = ?
                WHERE term_id = (SELECT id FROM term WHERE term_key = ?)
                  AND (mastered_at IS NULL OR mastered_at <= ?)
                """, mastered ? 1 : 0, occurredAt, termKey, occurredAt);
    }

    /** 직접 고친 뜻·메모. 뜻이 null 이면 뜻은 그대로 두고, 메모는 받은 값으로 바꾼다. 담겨 있지 않으면 무시한다. */
    public void edit(String termKey, String meaning, String note, long now) {
        Term term = terms.findByKey(termKey).orElse(null);
        if (term == null || !contains(term.id())) {
            return;
        }
        jdbc.update("UPDATE vocab_entry SET note = ? WHERE term_id = ?", note, term.id());
        if (meaning != null) {
            terms.setManualMeaning(term.id(), meaning, now);
        }
    }

    /** FAILED·PENDING 표현의 생성을 다시 시도한다. */
    public int retry() {
        return generation.retryAll();
    }

    private boolean contains(long termId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM vocab_entry WHERE term_id = ?", Integer.class,
                termId);
        return count != null && count > 0;
    }

    /** 생성기·음성은 커밋된 행을 읽어야 하므로 트랜잭션이 끝난 뒤에 깨운다. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
