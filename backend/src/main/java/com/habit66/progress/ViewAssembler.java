package com.habit66.progress;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;
import com.habit66.progress.DaySnapshot.Mark;
import com.habit66.progress.Views.Dashboard;
import com.habit66.progress.Views.HeatCell;
import com.habit66.progress.Views.Heatmap;
import com.habit66.progress.Views.SentenceDto;
import com.habit66.progress.Views.SessionView;
import com.habit66.tts.TtsService;
import com.habit66.tts.TtsService.Clip;
import com.habit66.vocab.TermStore;
import com.habit66.vocab.TermStore.SentenceTerm;

/** {@link DaySnapshot}을 응답 모양으로 투영한다. 규칙은 없다. */
@Component
public class ViewAssembler {

    private static final String REVIEW_TITLE = "누적 복습";
    private static final String PREVIEW_TITLE = "미리보기 복습";

    private final TtsService tts;
    private final TermStore terms;

    public ViewAssembler(TtsService tts, TermStore terms) {
        this.tts = tts;
        this.terms = terms;
    }

    public Dashboard dashboard(DaySnapshot s) {
        LessonView lesson = s.todayLesson();
        List<Long> todayIds = lesson == null ? List.of() : lesson.sentenceIds();
        return new Dashboard(
                s.today().toString(), s.serverTime(), s.validFrom(), s.validUntil(),
                s.currentDay(), s.totalDays(), s.lessonDay(), s.maxLessonDay(),
                s.contentMissing(), s.programComplete(),
                s.streak(),
                new Views.TodayProgress(todayIds.size(), todayIds, s.audioSeenIds(), s.cardSeenIds()),
                new Views.ReviewGate(s.gate().required(), s.gate().cleared(), s.gate().yesterdayDay(),
                        s.gate().unlockIds(), s.gate().seenReviewIds()),
                new Views.ReviewProgress(s.review().days(), s.review().sentences().size(), s.review().seen()),
                new Views.MorningLock(s.morningLock().windowStart(), s.morningLock().windowEnd(),
                        s.morningLock().state(), sentences(s.morningLock().sentences(), s)),
                new Views.Focus(s.focus().secondsToday(), s.focus().startedAts()));
    }

    public SessionView todaySession(DaySnapshot s) {
        LessonView lesson = s.todayLesson();
        if (lesson == null) {
            return new SessionView("TODAY", List.of(), "", false, List.of());
        }
        return new SessionView("TODAY", List.of(lesson.day()), lesson.title(), false,
                sentences(lesson.sentences(), s));
    }

    public SessionView reviewSession(DaySnapshot s) {
        return new SessionView("REVIEW", s.review().days(), s.review().preview() ? PREVIEW_TITLE : REVIEW_TITLE,
                s.review().preview(), sentences(s.review().sentences(), s));
    }

    /** 한 레슨의 문장(음성·암기 상태·표현 포함). 말하기 연습이 쓴다. */
    public List<SentenceDto> lessonSentences(LessonView lesson, DaySnapshot s) {
        return sentences(lesson.sentences(), s);
    }

    public Heatmap heatmap(DaySnapshot s) {
        List<HeatCell> cells = s.cells().stream()
                .map(c -> new HeatCell(c.day(), c.date().toString(), c.status().name()))
                .toList();
        return new Heatmap(s.startDate().toString(), s.currentDay(), s.programComplete(), s.streak().current(),
                s.streak().longest(), s.achievedDays(), s.streak().freezesLeft(), cells);
    }

    private List<SentenceDto> sentences(List<SentenceView> views, DaySnapshot s) {
        Map<Long, List<SentenceTerm>> termsById = terms.termsOf(views.stream().map(SentenceView::id).toList());
        return views.stream()
                .map(v -> sentence(v, s.marks().get(v.id()), termsById.getOrDefault(v.id(), List.of())))
                .toList();
    }

    private SentenceDto sentence(SentenceView v, Mark mark, List<SentenceTerm> sentenceTerms) {
        Clip clip = tts.clipOf(v);
        return new SentenceDto(v.id(), v.day(), v.seq(), v.speaker(), v.enText(), v.koWordOrder(), v.koNatural(),
                v.core(), mark == null ? "NEW" : mark.status(), mark == null ? null : mark.changedAt(),
                clip.audioUrl(), clip.durationMs(), clip.durationEstimated(), sentenceTerms);
    }
}
