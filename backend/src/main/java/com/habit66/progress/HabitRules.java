package com.habit66.progress;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;

import com.habit66.progress.DaySnapshot.Cell;
import com.habit66.progress.DaySnapshot.CellStatus;
import com.habit66.progress.DaySnapshot.Streak;

/**
 * 하루 상태의 규칙 전부. 순수 함수뿐이고 아무것도 저장하지 않는다(매 요청 다시 계산한다).
 */
final class HabitRules {

    static final int PERIOD_DAYS = 30;
    static final int FREEZES_PER_PERIOD = 2;
    static final int REVIEW_DAYS = 4;

    record ReviewPlan(List<Integer> days, boolean preview) {
    }

    record Walk(Streak streak, int achievedDays, List<Cell> cells) {
    }

    private HabitRules() {
    }

    /** 시작일부터 지난 날 + 1 을 1~totalDays 로 자른다. 하루를 빠져도 달력대로 넘어간다. */
    static int currentDay(LocalDate start, LocalDate date, int totalDays) {
        long day = ChronoUnit.DAYS.between(start, date) + 1;
        return (int) Math.max(1, Math.min(totalDays, day));
    }

    static boolean programComplete(LocalDate start, LocalDate today, int totalDays) {
        return ChronoUnit.DAYS.between(start, today) >= totalDays;
    }

    /** 등록된 일차 중 currentDay 이하의 최댓값. 없으면 0. */
    static int lessonDay(NavigableSet<Integer> days, int currentDay) {
        Integer floor = days.floor(currentDay);
        return floor == null ? 0 : floor;
    }

    /** "어제 레슨" = lessonDay 보다 작은 등록 일차 중 가장 큰 것. 없으면 null(게이트 없음). */
    static Integer gateDay(NavigableSet<Integer> days, int lessonDay) {
        return lessonDay <= 0 ? null : days.lower(lessonDay);
    }

    /** 복습 큐의 일차: lessonDay 직전 등록 일차 최대 4개, 최근 일차 먼저. 이전 일차가 없으면 오늘 레슨 미리보기. */
    static ReviewPlan reviewPlan(NavigableSet<Integer> days, int lessonDay) {
        if (lessonDay <= 0) {
            return new ReviewPlan(List.of(), false);
        }
        List<Integer> picked = days.headSet(lessonDay, false).descendingSet().stream().limit(REVIEW_DAYS).toList();
        return picked.isEmpty() ? new ReviewPlan(List.of(lessonDay), true) : new ReviewPlan(picked, false);
    }

    /** 레슨 문장을 전부 봤는가. 빈 레슨은 완주로 치지 않는다. */
    static boolean completes(Collection<Long> lessonSentenceIds, Set<Long> seenIds) {
        return !lessonSentenceIds.isEmpty() && seenIds.containsAll(lessonSentenceIds);
    }

    /**
     * 시작일부터 걷는다. 지난 날: 달성이면 +1, 미달성이면 (스트릭 > 0 이고 그 30일 구간 프리즈가 남았을 때만) 프리즈로 유지,
     * 아니면 0. 오늘은 달성했을 때만 +1 하고 끊지 않는다. 프로그램 범위(시작일 + totalDays - 1) 밖의 날은 세지 않는다.
     */
    static Walk walk(LocalDate start, LocalDate today, Set<LocalDate> achieved, int totalDays) {
        int[] freezes = new int[(totalDays + PERIOD_DAYS - 1) / PERIOD_DAYS];
        Arrays.fill(freezes, FREEZES_PER_PERIOD);

        int streak = 0;
        int longest = 0;
        int achievedDays = 0;
        boolean todayInProgram = false;
        List<Cell> cells = new ArrayList<>(totalDays);
        for (int day = 1; day <= totalDays; day++) {
            LocalDate date = start.plusDays(day - 1L);
            int period = (day - 1) / PERIOD_DAYS;
            boolean done = achieved.contains(date);
            CellStatus status;
            if (date.isBefore(today)) {
                if (done) {
                    streak++;
                    achievedDays++;
                    status = CellStatus.ACHIEVED;
                } else if (streak > 0 && freezes[period] > 0) {
                    freezes[period]--;
                    status = CellStatus.FROZEN;
                } else {
                    streak = 0;
                    status = CellStatus.MISSED;
                }
            } else if (date.equals(today)) {
                todayInProgram = true;
                if (done) {
                    streak++;
                    achievedDays++;
                    status = CellStatus.ACHIEVED;
                } else {
                    status = CellStatus.TODAY;
                }
            } else {
                status = CellStatus.FUTURE;
            }
            longest = Math.max(longest, streak);
            cells.add(new Cell(day, date, status));
        }

        boolean achievedToday = achieved.contains(today);
        int ifAchievedToday = todayInProgram && !achievedToday ? streak + 1 : streak;
        int freezesLeft = freezes[(currentDay(start, today, totalDays) - 1) / PERIOD_DAYS];
        return new Walk(new Streak(streak, ifAchievedToday, longest, achievedToday, freezesLeft, FREEZES_PER_PERIOD),
                achievedDays, List.copyOf(cells));
    }
}
