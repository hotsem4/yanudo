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
 * 말하기 연습(SPEC F-09, 부가 기능). 배운 Day(오늘 레슨까지)의 문장을 한 문장씩 5번 맞게 말한다.
 * 연습 기록은 SPEAK 이벤트로만 쓰고(EventService), 여기서는 읽기만 한다. 습관·게이트·스트릭과는 무관하다.
 */
@RestController
@RequestMapping("/api/v1/speak")
public class SpeakController {

    /** @param doneIds 연습을 끝낸 문장 id(프론트가 아직 보내지 않은 완료와 겹치지 않게 합친다) */
    public record SpeakDay(int day, String title, List<Long> sentenceIds, List<Long> doneIds) {
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

    /** 배운 Day 목록(최근 먼저)과 Day 별 연습을 끝낸 문장 수. */
    @GetMapping
    public SpeakOverview overview() {
        DaySnapshot s = dayState.snapshot();
        Set<Long> done = Set.copyOf(doneIds());
        List<SpeakDay> days = learned(s).stream()
                .sorted((a, b) -> Integer.compare(b.day(), a.day()))
                .map(lesson -> new SpeakDay(lesson.day(), lesson.title(), lesson.sentenceIds(),
                        lesson.sentenceIds().stream().filter(done::contains).toList()))
                .toList();
        return new SpeakOverview(s.lessonDay(), days);
    }

    /** 한 Day 의 문장(음성 포함)과 연습을 끝낸 문장 id. 아직 배우지 않은 Day 는 404. */
    @GetMapping("/days/{day}")
    public SpeakLesson day(@PathVariable int day) {
        DaySnapshot s = dayState.snapshot();
        LessonView lesson = learned(s).stream().filter(l -> l.day() == day).findFirst()
                .orElseThrow(() -> ApiException.notFound("SPEAK_DAY_LOCKED", "아직 배우지 않은 Day 예요."));
        Set<Long> done = Set.copyOf(doneIds());
        List<Long> doneInLesson = lesson.sentenceIds().stream().filter(done::contains).toList();
        return new SpeakLesson(lesson.day(), lesson.title(), views.lessonSentences(lesson, s), doneInLesson);
    }

    /** 오늘 레슨 일차까지 등록된 레슨. */
    private List<LessonView> learned(DaySnapshot s) {
        return catalog.current().lessons().stream().filter(l -> l.day() <= s.lessonDay()).toList();
    }

    private List<Long> doneIds() {
        return jdbc.queryForList("SELECT DISTINCT sentence_id FROM speak_practice", Long.class);
    }
}
