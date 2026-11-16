package com.habit66.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.habit66.common.LogicalClock;
import com.habit66.common.WriteLock;
import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;
import com.habit66.content.LessonImport.LessonIn;
import com.habit66.content.LessonImport.SentenceIn;
import com.habit66.content.LessonImport.TermIn;
import com.habit66.vocab.TermStore;
import com.habit66.vocab.TermStore.Example;

/**
 * lesson, sentence, sentence_term 테이블의 유일한 쓰기 소유자. 읽기는 메모리의 {@link Catalog} 하나로 한다.
 * 표현(term) 내용은 여기서 직접 쓰지 않고 {@link TermStore#upsertFromContent}로만 넣는다.
 */
@Service
public class CatalogService {

    public record ImportResult(int lessons, int created, int updated) {
    }

    private final LessonRepository lessons;
    private final SentenceRepository sentences;
    private final LogicalClock clock;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final WriteLock writes;
    private final JdbcTemplate jdbc;
    private final TermStore termStore;

    private volatile Catalog cached;

    public CatalogService(LessonRepository lessons, SentenceRepository sentences, LogicalClock clock,
            PlatformTransactionManager txManager, ApplicationEventPublisher events, WriteLock writes,
            JdbcTemplate jdbc, TermStore termStore) {
        this.lessons = lessons;
        this.sentences = sentences;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.events = events;
        this.writes = writes;
        this.jdbc = jdbc;
        this.termStore = termStore;
    }

    public Catalog current() {
        Catalog catalog = cached;
        if (catalog == null) {
            // 임포트와 같은 쓰기 잠금 안에서 처음 읽는다. 따로 잠그면 첫 읽기가 방금 끝난 임포트 결과를 옛 내용으로 덮을 수 있다.
            catalog = writes.call(() -> {
                if (cached == null) {
                    cached = load();
                }
                return cached;
            });
        }
        return catalog;
    }

    /**
     * 같은 (day, seq)면 텍스트만 바꾼다(문장 id 유지). JSON 에 없는 기존 seq 는 지우지 않는다.
     * {@code updated}는 내용이 실제로 달라진 문장 수다.
     */
    public ImportResult importLessons(LessonImport data) {
        data.validate();
        // 저장과 카탈로그 교체를 한 덩어리로 묶는다. 이벤트 수용은 임포트 전이나 후의 카탈로그만 본다.
        ImportResult result = writes.call(() -> {
            ImportResult saved = store(data);
            cached = load();
            return saved;
        });
        events.publishEvent(new CatalogChanged(result.created(), result.updated()));
        return result;
    }

    private ImportResult store(LessonImport data) {
        long now = clock.now();
        return tx.execute(status -> {
            int created = 0;
            int updated = 0;
            for (LessonIn in : data.lessons()) {
                String title = in.title() == null || in.title().isBlank() ? "Day " + in.day() : in.title().trim();
                String theme = in.theme() == null || in.theme().isBlank() ? null : in.theme().trim();
                Lesson lesson = lessons.findByDayNumber(in.day()).orElse(null);
                if (lesson == null) {
                    lesson = lessons.save(new Lesson(in.day(), title, theme, now));
                } else if (!title.equals(lesson.getTitle()) || !java.util.Objects.equals(theme, lesson.getTheme())) {
                    lesson.retitle(title, theme, now);
                }
                Map<Integer, Sentence> bySeq = new HashMap<>();
                for (Sentence existing : sentences.findByLessonId(lesson.getId())) {
                    bySeq.put(existing.getSeq(), existing);
                }
                Map<Sentence, List<TermIn>> termsToLink = new LinkedHashMap<>();
                for (int i = 0; i < in.sentences().size(); i++) {
                    SentenceIn s = in.sentences().get(i);
                    int seq = i + 1;
                    String en = s.enText().trim();
                    String order = s.koWordOrder().trim();
                    String natural = s.koNatural().trim();
                    boolean core = Boolean.TRUE.equals(s.core());
                    Sentence sentence = bySeq.get(seq);
                    if (sentence == null) {
                        sentence = sentences.save(
                                new Sentence(lesson.getId(), seq, s.speaker(), en, order, natural, core, now));
                        created++;
                    } else if (!sentence.sameText(s.speaker(), en, order, natural, core)) {
                        sentence.rewrite(s.speaker(), en, order, natural, core, now);
                        updated++;
                    }
                    if (s.terms() != null) {
                        termsToLink.put(sentence, s.terms());
                    }
                }
                // sentence_term 은 JDBC 로 쓰므로, 그 전에 문장 행이 DB 에 있어야 한다(외래 키).
                sentences.flush();
                termsToLink.forEach((sentence, terms) -> linkTerms(sentence.getId(), terms, now));
            }
            return new ImportResult(data.lessons().size(), created, updated);
        });
    }

    /**
     * 이 문장의 표현 연결을 JSON 의 목록으로 바꾼다. JSON 에 terms 키가 없는 문장(옛 형식)은 여기 오지 않으므로
     * 기존 연결이 그대로 남는다. 표현 자체는 지우지 않는다(단어장에 담겨 있을 수 있다).
     */
    private void linkTerms(long sentenceId, List<TermIn> terms, long now) {
        jdbc.update("DELETE FROM sentence_term WHERE sentence_id = ?", sentenceId);
        for (TermIn term : terms) {
            List<Example> examples = term.examples().stream().map(e -> new Example(e.en().strip(), e.ko().strip()))
                    .toList();
            long termId = termStore.upsertFromContent(term.text(), term.meaning().strip(), term.nuance().strip(),
                    examples, now);
            jdbc.update("INSERT INTO sentence_term (sentence_id, term_id, form) VALUES (?, ?, ?)", sentenceId,
                    termId, term.formOrText());
        }
    }

    private Catalog load() {
        Map<Long, List<Sentence>> byLesson = sentences.findAll().stream()
                .collect(Collectors.groupingBy(Sentence::getLessonId));
        List<LessonView> views = new ArrayList<>();
        for (Lesson lesson : lessons.findAll()) {
            List<SentenceView> items = byLesson.getOrDefault(lesson.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(Sentence::getSeq))
                    .map(s -> new SentenceView(s.getId(), lesson.getDayNumber(), s.getSeq(), s.getSpeaker(),
                            s.getEnText(), s.getKoWordOrder(), s.getKoNatural(), s.isCore(), s.getCreatedAt()))
                    .toList();
            views.add(new LessonView(lesson.getDayNumber(), lesson.getTitle(), lesson.getTheme(), items));
        }
        return Catalog.of(views);
    }
}
