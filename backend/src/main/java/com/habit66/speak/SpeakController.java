package com.habit66.speak;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.ApiException;
import com.habit66.content.Catalog.LessonView;
import com.habit66.content.CatalogService;
import com.habit66.progress.DaySnapshot;
import com.habit66.progress.DaySnapshot.Makeup;
import com.habit66.progress.DayStateService;
import com.habit66.progress.ViewAssembler;
import com.habit66.progress.Views.SentenceDto;

/**
 * 말하기 연습(SPEC F-09). 지난 Day 는 그날 달성했으면 바로, 빼먹었으면 보충(문장마다 5번, 미리 알린 날은 1번)을
 * 끝내야 열린다. 오늘 Day 는 오늘의 10문장을 끝내면 열린다. 기록은 SPEAK 이벤트로만 쓰고(EventService) 여기서는 읽기만 한다.
 */
@RestController
@RequestMapping("/api/v1/speak")
public class SpeakController {

    /** 보충이 아닌 연습에서 문장마다 맞게 말할 횟수. */
    private static final int PRACTICE_REPS = 5;

    /** OPEN: 자유 연습. MAKEUP: 빼먹은 날 보충. TODAY_LOCKED: 오늘의 10문장을 끝내기 전. */
    public enum State { OPEN, MAKEUP, TODAY_LOCKED }

    /**
     * @param doneIds  OPEN 은 한 번이라도 연습을 끝낸 문장, MAKEUP 은 보충으로 센 문장
     * @param reps     문장마다 맞게 말할 횟수(보충이 아닌 연습과 빼먹은 날은 5, 미리 알린 날은 1)
     * @param seen     TODAY_LOCKED 일 때 오늘의 10문장 진행(오디오·카드 중 많이 본 쪽)
     * @param deadline   MAKEUP 일 때 이날까지 끝내면 그날 스트릭이 이어진다
     * @param repairable MAKEUP 일 때 이 보충이 실제로 스트릭을 지키는가(기한 안이고 그 전날까지 스트릭이 있음)
     */
    public record SpeakDay(int day, String title, List<Long> sentenceIds, List<Long> doneIds, State state, int reps,
            int seen, boolean rest, String deadline, boolean repairable) {
    }

    public record SpeakOverview(int lessonDay, List<SpeakDay> days) {
    }

    public record SpeakLesson(int day, String title, List<SentenceDto> sentences, List<Long> doneIds, State state,
            int reps, boolean rest, String deadline, boolean repairable) {
    }

    private final DayStateService dayState;
    private final CatalogService catalog;
    private final ViewAssembler views;
    private final JdbcTemplate jdbc;

    public SpeakController(DayStateService dayState, CatalogService catalog, ViewAssembler views, JdbcTemplate jdbc) {
        this.dayState = dayState;
        this.catalog = catalog;
        this.views = views;
        this.jdbc = jdbc;
    }

    /** 오늘 레슨까지의 Day 목록(최근 먼저). */
    @GetMapping
    public SpeakOverview overview() {
        DaySnapshot s = dayState.snapshot();
        Set<Long> practiced = Set.copyOf(practicedIds());
        List<SpeakDay> days = upToToday(s).stream()
                .sorted((a, b) -> Integer.compare(b.day(), a.day()))
                .map(lesson -> row(lesson, s, practiced))
                .toList();
        return new SpeakOverview(s.lessonDay(), days);
    }

    /** 한 Day 의 문장(음성 포함)과 진행. 오늘의 10문장을 끝내기 전의 오늘 Day 와 앞으로의 Day 는 404. */
    @GetMapping("/days/{day}")
    public SpeakLesson day(@PathVariable int day) {
        DaySnapshot s = dayState.snapshot();
        LessonView lesson = upToToday(s).stream().filter(l -> l.day() == day).findFirst()
                .orElseThrow(SpeakController::locked);
        SpeakDay row = row(lesson, s, Set.copyOf(practicedIds()));
        if (row.state() == State.TODAY_LOCKED) {
            throw locked();
        }
        return new SpeakLesson(lesson.day(), lesson.title(), views.lessonSentences(lesson, s), row.doneIds(),
                row.state(), row.reps(), row.rest(), row.deadline(), row.repairable());
    }

    private SpeakDay row(LessonView lesson, DaySnapshot s, Set<Long> practiced) {
        List<Long> ids = lesson.sentenceIds();
        Makeup makeup = s.makeups().stream().filter(m -> m.day() == lesson.day() && !m.done()).findFirst().orElse(null);
        if (makeup != null) {
            return new SpeakDay(lesson.day(), lesson.title(), ids, makeup.doneIds(), State.MAKEUP, makeup.reps(), 0,
                    makeup.rest(), makeup.deadline().toString(), makeup.keepsStreak());
        }
        boolean today = lesson.day() == s.lessonDay() && s.lessonDay() == s.currentDay();
        List<Long> done = ids.stream().filter(practiced::contains).toList();
        if (today && !s.streak().achievedToday() && !s.programComplete()) {
            int seen = Math.max(s.audioSeenIds().size(), s.cardSeenIds().size());
            return new SpeakDay(lesson.day(), lesson.title(), ids, done, State.TODAY_LOCKED, PRACTICE_REPS, seen,
                    false, null, false);
        }
        return new SpeakDay(lesson.day(), lesson.title(), ids, done, State.OPEN, PRACTICE_REPS, 0, false, null,
                false);
    }

    private static ApiException locked() {
        return ApiException.notFound("SPEAK_DAY_LOCKED", "오늘의 10문장을 끝내면 열려요.");
    }

    /** 오늘 레슨 일차까지 등록된 레슨. */
    private List<LessonView> upToToday(DaySnapshot s) {
        return catalog.current().lessons().stream().filter(l -> l.day() <= s.lessonDay()).toList();
    }

    /** 자유 연습을 끝낸 문장(쉬는 날 보충처럼 1번만 한 기록은 연습 완료로 치지 않는다). */
    private List<Long> practicedIds() {
        return jdbc.queryForList("SELECT DISTINCT sentence_id FROM speak_practice WHERE reps >= ?", Long.class,
                PRACTICE_REPS);
    }

}
