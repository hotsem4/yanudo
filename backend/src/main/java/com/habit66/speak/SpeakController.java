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
import com.habit66.progress.DayStateService;
import com.habit66.progress.ViewAssembler;
import com.habit66.progress.Views.SentenceDto;

/**
 * 말하기 연습(SPEC F-09, 부가 기능). 학습에서 10문장을 모두 본 Day 의 문장을 한 문장씩 5번 맞게 말한다.
 * 연습 기록은 SPEAK 이벤트로만 쓰고(EventService), 여기서는 읽기만 한다. 습관·게이트·스트릭과는 무관하다.
 */
@RestController
@RequestMapping("/api/v1/speak")
public class SpeakController {

    /**
     * @param doneIds 연습을 끝낸 문장 id(프론트가 아직 보내지 않은 완료와 겹치지 않게 합친다)
     * @param open    학습(오늘·복습, 오디오·카드 무관)에서 이 Day 문장을 모두 봐서 연습할 수 있는지
     * @param seen    학습에서 본 문장 수(잠긴 Day 에 "6/10" 처럼 보인다)
     */
    public record SpeakDay(int day, String title, List<Long> sentenceIds, List<Long> doneIds, boolean open, int seen) {
    }

    public record SpeakOverview(int lessonDay, List<SpeakDay> days) {
    }

    public record SpeakLesson(int day, String title, List<SentenceDto> sentences, List<Long> doneIds) {
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

    /** 오늘 레슨까지의 Day 목록(최근 먼저). 다 보지 않은 Day 는 잠긴 채로 본 문장 수만 준다. */
    @GetMapping
    public SpeakOverview overview() {
        DaySnapshot s = dayState.snapshot();
        Set<Long> done = Set.copyOf(doneIds());
        Set<Long> seen = seenIds();
        List<SpeakDay> days = upToToday(s).stream()
                .sorted((a, b) -> Integer.compare(b.day(), a.day()))
                .map(lesson -> new SpeakDay(lesson.day(), lesson.title(), lesson.sentenceIds(),
                        lesson.sentenceIds().stream().filter(done::contains).toList(),
                        isOpen(lesson, seen), (int) lesson.sentenceIds().stream().filter(seen::contains).count()))
                .toList();
        return new SpeakOverview(s.lessonDay(), days);
    }

    /** 한 Day 의 문장(음성 포함)과 연습을 끝낸 문장 id. 아직 10문장을 다 보지 않은 Day 는 404. */
    @GetMapping("/days/{day}")
    public SpeakLesson day(@PathVariable int day) {
        DaySnapshot s = dayState.snapshot();
        Set<Long> seen = seenIds();
        LessonView lesson = upToToday(s).stream().filter(l -> l.day() == day && isOpen(l, seen)).findFirst()
                .orElseThrow(() -> ApiException.notFound("SPEAK_DAY_LOCKED", "학습에서 이 Day 문장을 모두 보면 열려요."));
        Set<Long> done = Set.copyOf(doneIds());
        List<Long> doneInLesson = lesson.sentenceIds().stream().filter(done::contains).toList();
        return new SpeakLesson(lesson.day(), lesson.title(), views.lessonSentences(lesson, s), doneInLesson);
    }

    /** 오늘 레슨 일차까지 등록된 레슨. */
    private List<LessonView> upToToday(DaySnapshot s) {
        return catalog.current().lessons().stream().filter(l -> l.day() <= s.lessonDay()).toList();
    }

    /** 날짜가 지났다고 배운 것으로 치지 않는다. 오늘 10문장이나 누적 복습에서 실제로 다 봐야 열린다. */
    private static boolean isOpen(LessonView lesson, Set<Long> seen) {
        return !lesson.sentenceIds().isEmpty() && seen.containsAll(lesson.sentenceIds());
    }

    private Set<Long> seenIds() {
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT sentence_id FROM sentence_view", Long.class));
    }

    private List<Long> doneIds() {
        return jdbc.queryForList("SELECT DISTINCT sentence_id FROM speak_practice", Long.class);
    }
}
