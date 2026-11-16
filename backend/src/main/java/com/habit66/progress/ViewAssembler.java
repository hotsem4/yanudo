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
                s.contentMissing(), s.round(), s.roundDay(),
                s.streak(),
                new Views.TodayProgress(todayIds.size(), todayIds, s.audioSeenIds(), s.cardSeenIds(), s.spokenSeenIds()),
                new Views.ReviewGate(s.gate().required(), s.gate().cleared(), s.gate().yesterdayDay(),
                        s.gate().unlockIds(), s.gate().seenReviewIds()),
                new Views.ReviewProgress(s.review().days(), s.review().sentences().size(), s.review().seen()),
                new Views.MorningLock(s.morningLock().windowStart(), s.morningLock().windowEnd(),
                        s.morningLock().state(), sentences(s.morningLock().sentences(), s)),
                new Views.Focus(s.focus().secondsToday(), s.focus().startedAts()),
                new Views.RestDay(s.restDay().declaredToday(), s.restDay().left(), s.restDay().perPeriod(),
                        s.restDay().periodFrom(), s.restDay().periodTo()),
                // 순환 중 같은 레슨을 이틀 빼먹으면 날짜마다 보충이 생기지만 한 번 보충으로 함께 이어진다.
                // 그 레슨의 가장 이른(기한이 가장 급한) 한 줄만 내린다.
                s.makeups().stream()
                        .filter(m -> !m.done() && m.keepsStreak())
                        .filter(distinctDay())
                        .map(m -> new Views.MakeupDue(m.day(), m.date().toString(), m.rest(), m.reps(),
                                m.deadline().toString(), m.sentenceIds(), m.doneIds(), m.effect().name(),
                                m.streakIfDone()))
                        .toList());
    }

    private static java.util.function.Predicate<DaySnapshot.Makeup> distinctDay() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        return m -> seen.add(m.day());
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
        String roundStart = s.cells().isEmpty() ? s.startDate().toString() : s.cells().get(0).date().toString();
        return new Heatmap(s.startDate().toString(), s.currentDay(), s.round(), s.roundDay(), roundStart,
                s.streak().current(), s.streak().longest(), s.achievedDays(), s.streak().freezesLeft(), cells,
                s.pastRounds().stream().map(r -> new Views.PastRound(r.round(), r.achievedDays(), r.days())).toList());
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
