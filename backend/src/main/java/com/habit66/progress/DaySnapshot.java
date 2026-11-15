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
        Map<Long, Mark> marks) {

    public enum CellStatus { ACHIEVED, FROZEN, MISSED, TODAY, FUTURE }

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
