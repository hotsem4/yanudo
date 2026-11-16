package com.habit66.progress;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.habit66.progress.DaySnapshot.Mark;

/** progress 테이블 읽기 전용 조회. 쓰기는 {@link EventService}만 한다. */
@Repository
class ProgressFacts {

    record ViewRow(long sentenceId, String session, String via) {
    }

    record FocusRow(long startedAt, long seconds) {
    }

    /** 말하기 연습을 끝낸 기록 하나(보충 판정용). */
    record SpeakRow(long sentenceId, LocalDate logDate, int reps) {
    }

    private final JdbcTemplate jdbc;

    ProgressFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<LocalDate> achievedDates() {
        return new HashSet<>(jdbc.query("SELECT log_date FROM day_achievement",
                (rs, i) -> LocalDate.parse(rs.getString(1))));
    }

    Set<LocalDate> restDays() {
        return new HashSet<>(jdbc.query("SELECT log_date FROM rest_day",
                (rs, i) -> LocalDate.parse(rs.getString(1))));
    }

    List<SpeakRow> speaks() {
        return jdbc.query("SELECT sentence_id, log_date, reps FROM speak_practice",
                (rs, i) -> new SpeakRow(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getInt(3)));
    }

    List<ViewRow> views(LocalDate date) {
        return jdbc.query("SELECT sentence_id, session, via FROM sentence_view WHERE log_date = ?",
                (rs, i) -> new ViewRow(rs.getLong(1), rs.getString(2), rs.getString(3)), date.toString());
    }

    Set<Long> seenIds(LocalDate date, String session, String via) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT sentence_id FROM sentence_view WHERE log_date = ? AND session = ? AND via = ?",
                Long.class, date.toString(), session, via));
    }

    Map<Long, Mark> marks() {
        Map<Long, Mark> marks = new HashMap<>();
        RowCallbackHandler collect = rs -> marks.put(rs.getLong(1), new Mark(rs.getString(2), rs.getLong(3)));
        jdbc.query("SELECT sentence_id, status, changed_at FROM sentence_mark", collect);
        return marks;
    }

    Optional<String> morningLock(LocalDate date) {
        return jdbc.queryForList("SELECT state FROM morning_lock WHERE log_date = ?", String.class, date.toString())
                .stream().findFirst();
    }

    List<FocusRow> focus(LocalDate date) {
        return jdbc.query("SELECT started_at, seconds FROM focus_session WHERE log_date = ? ORDER BY started_at",
                (rs, i) -> new FocusRow(rs.getLong(1), rs.getLong(2)), date.toString());
    }
}
