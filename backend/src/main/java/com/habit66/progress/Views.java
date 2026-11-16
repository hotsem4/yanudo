package com.habit66.progress;

import java.util.List;

import com.habit66.progress.DaySnapshot.Streak;
import com.habit66.vocab.TermStore.SentenceTerm;

/** SPEC 4장의 응답 모양. 컴포넌트 이름이 snake_case 로 바뀌어 그대로 JSON 필드가 된다. */
public final class Views {

    private Views() {
    }

    public record SentenceDto(long id, int day, int seq, String speaker, String enText, String koWordOrder,
            String koNatural, boolean core, String status, Long statusChangedAt, String audioUrl, long durationMs,
            boolean durationEstimated, List<SentenceTerm> terms) {
    }

    public record Dashboard(String today, long serverTime, long validFrom, long validUntil, int currentDay,
            int totalDays, int lessonDay, int maxLessonDay, boolean contentMissing, int round, int roundDay,
            Streak streak, TodayProgress todayProgress, ReviewGate reviewGate, ReviewProgress reviewProgress,
            MorningLock morningLock, Focus focus, RestDay restDay, List<MakeupDue> makeupsDue) {
    }

    /** "오늘은 어려워요" 상태. */
    public record RestDay(boolean declaredToday, int left, int perPeriod, int periodFrom, int periodTo) {
    }

    /** 끝내면 스트릭을 지키는(기한 안이고 그 전날까지 스트릭이 있는), 아직 끝내지 않은 보충. */
    public record MakeupDue(int day, String date, boolean rest, int reps, String deadline, List<Long> sentenceIds,
            List<Long> doneIds) {
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

    /**
     * 이번 라운드의 기록. cells 는 라운드 길이(66)만큼, day 는 라운드 안의 Day.
     *
     * @param pastRounds 끝난 라운드마다 달성한 날 수
     */
    public record Heatmap(String startDate, int currentDay, int round, int roundDay, String roundStart,
            int currentStreak, int longestStreak, int achievedDays, int freezesLeft, List<HeatCell> cells,
            List<PastRound> pastRounds) {
    }

    public record PastRound(int round, int achievedDays, int days) {
    }

    public record HeatCell(int day, String date, String status) {
    }
}
