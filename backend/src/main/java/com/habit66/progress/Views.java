package com.habit66.progress;

import java.util.List;

import com.habit66.progress.DaySnapshot.Streak;

/** SPEC 4장의 응답 모양. 컴포넌트 이름이 snake_case 로 바뀌어 그대로 JSON 필드가 된다. */
public final class Views {

    private Views() {
    }

    public record SentenceDto(long id, int day, int seq, String speaker, String enText, String koWordOrder,
            String koNatural, boolean core, String status, Long statusChangedAt, String audioUrl, long durationMs,
            boolean durationEstimated) {
    }

    public record Dashboard(String today, long serverTime, long validFrom, long validUntil, int currentDay,
            int totalDays, int lessonDay, int maxLessonDay, boolean contentMissing, boolean programComplete,
            Streak streak, TodayProgress todayProgress, ReviewGate reviewGate, ReviewProgress reviewProgress,
            MorningLock morningLock, Focus focus) {
    }

    public record TodayProgress(int total, List<Long> sentenceIds, List<Long> audioSeenIds, List<Long> cardSeenIds) {
    }

    public record ReviewGate(boolean required, boolean cleared, Integer yesterdayDay, List<Long> unlockIds,
            List<Long> seenReviewIds) {
    }

    public record ReviewProgress(List<Integer> days, int total, int seen) {
    }

    public record MorningLock(long windowStart, long windowEnd, String state, List<SentenceDto> sentences) {
    }

    public record Focus(long secondsToday, List<Long> startedAts) {
    }

    public record SessionView(String session, List<Integer> days, String title, boolean preview,
            List<SentenceDto> sentences) {
    }

    public record Heatmap(String startDate, int currentDay, boolean programComplete, int currentStreak,
            int longestStreak, int achievedDays, int freezesLeft, List<HeatCell> cells) {
    }

    public record HeatCell(int day, String date, String status) {
    }
}
