package com.habit66.progress;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.common.WriteLock;
import com.habit66.config.AppProperties;
import com.habit66.content.Catalog;
import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;
import com.habit66.content.CatalogService;
import com.habit66.progress.ClientEvent.Session;
import com.habit66.progress.ClientEvent.Via;
import com.habit66.progress.EventsRequest.Parsed;
import com.habit66.progress.EventsRequest.Rejected;
import com.habit66.vocab.VocabService;

/**
 * 모든 학습 쓰기의 유일한 입구. sentence_view, sentence_mark, focus_session, morning_lock, day_achievement 의
 * 쓰기 소유자이고, 단어장 이벤트는 같은 트랜잭션 안에서 {@link VocabService}에 넘긴다.
 * 각 표의 자연 키가 멱등을 보장하므로 같은 이벤트를 다시 보내도 결과가 같다.
 */
@Service
public class EventService {

    public record ApplyResult(List<String> accepted, List<Rejected> rejected) {
    }

    private record Completion(LocalDate date, int lessonDay, Via via) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CatalogService catalog;
    private final SettingsStore settings;
    private final LogicalClock clock;
    private final ProgressFacts facts;
    private final WriteLock writes;
    private final VocabService vocab;
    private final int totalDays;

    public EventService(JdbcTemplate jdbc, PlatformTransactionManager txManager, CatalogService catalog,
            SettingsStore settings, LogicalClock clock, ProgressFacts facts, WriteLock writes,
            VocabService vocab, AppProperties props) {
        this.writes = writes;
        this.vocab = vocab;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
        this.facts = facts;
        this.totalDays = props.totalDays();
    }

    /**
     * 형식이 맞는 이벤트를 한 트랜잭션으로 수용한다. 없는 문장을 가리키는 이벤트만 거절한다.
     * 카탈로그와 시작일을 읽는 것부터 커밋까지 쓰기 잠금 안에서 하므로, 임포트·시작일 변경과 섞이지 않는다.
     */
    public ApplyResult apply(Parsed parsed, long receivedAt) {
        if (parsed.events().isEmpty()) {
            return new ApplyResult(List.of(), List.copyOf(parsed.rejected()));
        }
        return writes.call(() -> applyLocked(parsed, receivedAt));
    }

    private ApplyResult applyLocked(Parsed parsed, long receivedAt) {
        Set<String> accepted = new LinkedHashSet<>();
        List<Rejected> rejected = new ArrayList<>(parsed.rejected());
        Catalog cat = catalog.current();
        LocalDate start = settings.startDate().orElseGet(() -> clock.dateOf(receivedAt));

        tx.executeWithoutResult(status -> {
            Map<Completion, Long> completions = new LinkedHashMap<>();
            for (ClientEvent event : parsed.events()) {
                // 미래 시각만 자른다(실제로 일어난 시각은 받은 시각보다 늦을 수 없다). 늦게 도착한 어제 기록은 어제 날짜에 들어간다.
                long at = Math.min(event.occurredAt(), receivedAt);
                boolean ok;
                if (event instanceof ClientEvent.View view) {
                    ok = applyView(view, at, receivedAt, cat, start, completions);
                } else if (event instanceof ClientEvent.Mark mark) {
                    ok = applyMark(mark, at, cat);
                } else if (event instanceof ClientEvent.Focus focus) {
                    ok = applyFocus(focus, at, receivedAt);
                } else if (event instanceof ClientEvent.MorningLock lock) {
                    ok = applyMorningLock(lock, at, receivedAt);
                } else if (event instanceof ClientEvent.Speak speak) {
                    ok = applySpeak(speak, at, receivedAt, cat);
                } else {
                    ok = applyVocab(event, at, receivedAt);
                }
                if (ok) {
                    accepted.add(event.id());
                } else {
                    rejected.add(new Rejected(event.id(), "UNKNOWN_SENTENCE"));
                }
            }
            completions.forEach((completion, at) -> recordIfComplete(completion, at, cat));
        });
        return new ApplyResult(List.copyOf(accepted), rejected);
    }

    /**
     * 처음 받은 이벤트 id 인가. VIEW·MORNING_LOCK 은 날짜가 키라, 응답을 잃고 롤오버를 지나 다시 보내면
     * (미래 시각을 받은 시각으로 자르므로) 다른 날짜로 계산되어 두 번 기록될 수 있다. 자연 키로 무시된 이벤트도 여기 남긴다.
     */
    private boolean claim(String eventId, long receivedAt) {
        return jdbc.update("INSERT OR IGNORE INTO applied_event (id, applied_at) VALUES (?, ?)", eventId,
                receivedAt) > 0;
    }

    private boolean applyView(ClientEvent.View view, long at, long receivedAt, Catalog cat, LocalDate start,
            Map<Completion, Long> completions) {
        SentenceView sentence = cat.sentence(view.sentenceId()).orElse(null);
        if (sentence == null) {
            return false;
        }
        if (!claim(view.id(), receivedAt)) {
            return true;
        }
        LocalDate date = clock.dateOf(at);
        Session session = view.session();
        if (session == Session.TODAY) {
            // 그 날짜의 오늘 레슨이 아니면(롤오버 뒤에도 열어 둔 어제 큐 등) 복습으로 기록한다.
            int lessonDay = HabitRules.lessonDay(cat.days(), HabitRules.currentDay(start, date, totalDays));
            if (sentence.day() != lessonDay) {
                session = Session.REVIEW;
            } else {
                completions.merge(new Completion(date, lessonDay, view.via()), at, Math::max);
            }
        }
        jdbc.update("""
                INSERT OR IGNORE INTO sentence_view (log_date, sentence_id, session, via, event_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, date.toString(), sentence.id(), session.name(), view.via().name(), view.id(), receivedAt);
        return true;
    }

    private boolean applyMark(ClientEvent.Mark mark, long at, Catalog cat) {
        if (cat.sentence(mark.sentenceId()).isEmpty()) {
            return false;
        }
        // 같은 시각이면 나중에 온 것이 이긴다(프론트 오버레이와 같은 규칙). 재전송해도 결과는 같다.
        jdbc.update("""
                INSERT INTO sentence_mark (sentence_id, status, changed_at) VALUES (?, ?, ?)
                ON CONFLICT(sentence_id) DO UPDATE SET status = excluded.status, changed_at = excluded.changed_at
                WHERE excluded.changed_at >= sentence_mark.changed_at
                """, mark.sentenceId(), mark.status().name(), at);
        return true;
    }

    private boolean applyFocus(ClientEvent.Focus focus, long at, long receivedAt) {
        jdbc.update("""
                INSERT INTO focus_session (started_at, log_date, seconds, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(started_at) DO UPDATE SET seconds = excluded.seconds, log_date = excluded.log_date,
                    updated_at = excluded.updated_at
                WHERE excluded.seconds > focus_session.seconds
                """, focus.startedAt(), clock.dateOf(at).toString(), focus.seconds(), receivedAt);
        return true;
    }

    private boolean applyMorningLock(ClientEvent.MorningLock lock, long at, long receivedAt) {
        if (!claim(lock.id(), receivedAt)) {
            return true;
        }
        jdbc.update("INSERT OR IGNORE INTO morning_lock (log_date, state, decided_at) VALUES (?, ?, ?)",
                clock.dateOf(at).toString(), lock.result().name(), at);
        return true;
    }

    /** 단어장 이벤트. 없는 문장·담기지 않은 표현을 가리켜도 거절하지 않는다(그 부분만 무시한다). */
    /** 말하기 연습 기록. 이벤트 id 로 멱등(같은 문장을 다시 연습하면 새 행이 쌓인다). */
    private boolean applySpeak(ClientEvent.Speak speak, long at, long receivedAt, Catalog cat) {
        if (cat.sentence(speak.sentenceId()).isEmpty()) {
            return false;
        }
        jdbc.update("""
                INSERT OR IGNORE INTO speak_practice (sentence_id, log_date, reps, event_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, speak.sentenceId(), clock.dateOf(at).toString(), speak.reps(), speak.id(), receivedAt);
        return true;
    }

    private boolean applyVocab(ClientEvent event, long at, long receivedAt) {
        if (!vocab.claim(event.id(), receivedAt)) {
            return true;
        }
        if (event instanceof ClientEvent.VocabAdd add) {
            vocab.add(add.text(), add.sentenceId(), add.meaning(), add.note(), at, receivedAt);
        } else if (event instanceof ClientEvent.VocabRemove remove) {
            vocab.remove(remove.termKey());
        } else if (event instanceof ClientEvent.VocabMaster master) {
            vocab.master(master.termKey(), master.mastered(), at);
        } else if (event instanceof ClientEvent.VocabEdit edit) {
            vocab.edit(edit.termKey(), edit.meaning(), edit.note(), receivedAt);
        }
        return true;
    }

    /** 완주한 순간 한 번 기록한다. 이후 콘텐츠가 바뀌어도 이 행은 그대로다. */
    private void recordIfComplete(Completion completion, long achievedAt, Catalog cat) {
        List<Long> lessonIds = cat.lesson(completion.lessonDay()).map(LessonView::sentenceIds).orElse(List.of());
        Set<Long> seen = facts.seenIds(completion.date(), Session.TODAY.name(), completion.via().name());
        if (HabitRules.completes(lessonIds, seen)) {
            jdbc.update("""
                    INSERT OR IGNORE INTO day_achievement (log_date, lesson_day, via, achieved_at)
                    VALUES (?, ?, ?, ?)
                    """, completion.date().toString(), completion.lessonDay(), completion.via().name(), achievedAt);
        }
    }
}
