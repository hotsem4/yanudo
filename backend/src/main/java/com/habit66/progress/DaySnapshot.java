package com.habit66.progress;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;

/**
 * 한 시점의 하루 상태 전부. 대시보드·세션·히트맵·이벤트 응답·리마인더가 모두 이 값의 투영이다.
 *
 * @param todayLesson 오늘 레슨. 등록된 레슨이 없으면 null
 */
public record DaySnapshot(
        LocalDate today,
        long serverTime,
        long validFrom,
        long validUntil,
        LocalDate startDate,
        int currentDay,
        int totalDays,
        int lessonDay,
        int maxLessonDay,
        boolean contentMissing,
        boolean programComplete,
        Streak streak,
        int achievedDays,
        List<Cell> cells,
        LessonView todayLesson,
        List<Long> audioSeenIds,
        List<Long> cardSeenIds,
        Gate gate,
        Review review,
        MorningLock morningLock,
        Focus focus,
        Map<Long, Mark> marks,
        List<Makeup> makeups,
        RestDay restDay) {

    /** REPAIRED: 빼먹었지만 기한 안에 보충해서 스트릭이 이어진 날(프리즈처럼 늘지도 끊기지도 않는다). */
    public enum CellStatus { ACHIEVED, FROZEN, REPAIRED, MISSED, TODAY, FUTURE }

    /**
     * 지난 날 중 달성하지 못한 날의 보충. 그 날짜의 레슨(Day = 시작일부터 지난 날 + 1)을 날짜 다음부터
     * 문장마다 reps 번 이상 말하면 끝난다. deadline 까지 끝내면 그날 스트릭이 이어진다.
     *
     * @param rest        그날 미리 쉬는 날로 알렸는가(그러면 reps 1, 아니면 5)
     * @param doneOn      모든 문장을 끝낸 날(아직이면 null)
     * @param keepsStreak 이 보충이 실제로 스트릭을 지키는가(지켰는가). 기한 안이어도 그 전날까지 스트릭이 0 이면
     *                    (처음부터 안 했거나 앞에서 이미 끊겼으면) 지킬 것이 없어 false 다. 이때 보충은 Day 를 열기만 한다.
     */
    public record Makeup(int day, LocalDate date, boolean rest, int reps, LocalDate deadline, List<Long> sentenceIds,
            List<Long> doneIds, LocalDate doneOn, boolean keepsStreak) {

        public boolean done() {
            return doneOn != null;
        }

        /** 기한 안에 끝냈는가(그날은 REPAIRED 가 된다). */
        public boolean repairs() {
            return doneOn != null && !doneOn.isAfter(deadline);
        }

        Makeup withKeepsStreak(boolean keeps) {
            return new Makeup(day, date, rest, reps, deadline, sentenceIds, doneIds, doneOn, keeps);
        }
    }

    /** "오늘은 어려워요": 오늘 이미 알렸는지와 이번 30일 구간에 남은 횟수. */
    public record RestDay(boolean declaredToday, int left, int perPeriod) {
    }

    public record Streak(int current, int ifAchievedToday, int longest, boolean achievedToday, int freezesLeft,
            int freezesPerPeriod) {
    }

    public record Cell(int day, LocalDate date, CellStatus status) {
    }

    /** @param seenReviewIds 오늘 REVIEW 세션에서 본 문장 전부(게이트 대상이 아닌 것도 포함) */
    public record Gate(boolean required, boolean cleared, Integer yesterdayDay, List<Long> unlockIds,
            List<Long> seenReviewIds) {
    }

    public record Review(List<Integer> days, boolean preview, List<SentenceView> sentences, int seen) {
    }

    public record MorningLock(long windowStart, long windowEnd, String state, List<SentenceView> sentences) {
    }

    public record Focus(long secondsToday, List<Long> startedAts) {
    }

    public record Mark(String status, long changedAt) {
    }
}
