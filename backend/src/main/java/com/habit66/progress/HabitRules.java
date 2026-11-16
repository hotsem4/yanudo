package com.habit66.progress;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;

import com.habit66.progress.DaySnapshot.Cell;
import com.habit66.progress.DaySnapshot.CellStatus;
import com.habit66.progress.DaySnapshot.Makeup;
import com.habit66.progress.DaySnapshot.Streak;
import com.habit66.progress.ProgressFacts.SpeakRow;

/**
 * 하루 상태의 규칙 전부. 순수 함수뿐이고 아무것도 저장하지 않는다(매 요청 다시 계산한다).
 */
final class HabitRules {

    static final int PERIOD_DAYS = 30;
    static final int FREEZES_PER_PERIOD = 2;
    static final int REVIEW_DAYS = 4;
    /** 빼먹은 날 보충: 문장마다 이만큼 맞게 말한다. 미리 알린 날은 1번. */
    static final int MAKEUP_REPS = 5;
    static final int MAKEUP_REPS_REST = 1;
    /** 빼먹은 날 다음 날부터 이 날수 안에 보충을 끝내면 그날 스트릭이 이어진다. */
    static final int REPAIR_DAYS = 3;
    static final int REST_DAYS_PER_PERIOD = 4;

    record ReviewPlan(List<Integer> days, boolean preview) {
    }

    /** @param streakBefore 날짜마다 그날을 처리하기 직전의 스트릭(보충이 스트릭을 지키는지 판단할 때 쓴다) */
    record Walk(Streak streak, int achievedDays, List<Cell> cells, Map<LocalDate, Integer> streakBefore) {
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

    /** 그 날짜가 속한 30일 구간(0부터). */
    static int period(LocalDate start, LocalDate date, int totalDays) {
        return (currentDay(start, date, totalDays) - 1) / PERIOD_DAYS;
    }

    /**
     * 그 날짜의 구간에서 이미 쓴 "오늘은 어려워요" 횟수. 알린 뒤 그날 달성한 날은 쓰지 않은 것으로 친다.
     * 프로그램 밖 날짜(시작일 전, 시작일 + totalDays 이후)의 기록은 세지 않는다(시작일을 옮겨도 마지막 구간을 잠식하지 않게).
     */
    static int restDaysUsed(LocalDate start, LocalDate date, Collection<LocalDate> restDays, Set<LocalDate> achieved,
            int totalDays) {
        int period = period(start, date, totalDays);
        LocalDate end = start.plusDays(totalDays);
        return (int) restDays.stream()
                .filter(d -> !d.isBefore(start) && d.isBefore(end) && !achieved.contains(d))
                .filter(d -> period(start, d, totalDays) == period)
                .count();
    }

    /**
     * 지난 날 중 달성하지 못한 날마다 보충 상태를 만든다(Day 순). 그 날짜의 레슨은 Day = 시작일부터 지난 날 + 1 이다.
     * 보충은 그 날짜 다음 날부터 한 연습만 센다(그날 안에는 그냥 오늘 공부를 하면 된다).
     *
     * @param lessons Day → 문장 id(등록된 레슨만)
     */
    static List<Makeup> makeups(LocalDate start, LocalDate today, int totalDays, Map<Integer, List<Long>> lessons,
            Set<LocalDate> achieved, Set<LocalDate> restDays, List<SpeakRow> speaks) {
        Map<Long, List<SpeakRow>> bySentence = new HashMap<>();
        for (SpeakRow row : speaks) {
            bySentence.computeIfAbsent(row.sentenceId(), k -> new ArrayList<>()).add(row);
        }
        List<Makeup> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Long>> e : new TreeMap<>(lessons).entrySet()) {
            int day = e.getKey();
            List<Long> ids = e.getValue();
            LocalDate date = start.plusDays(day - 1L);
            if (day > totalDays || ids.isEmpty() || !date.isBefore(today) || achieved.contains(date)) {
                continue;
            }
            boolean rest = restDays.contains(date);
            int reps = rest ? MAKEUP_REPS_REST : MAKEUP_REPS;
            List<Long> doneIds = new ArrayList<>();
            LocalDate doneOn = null;
            boolean all = true;
            for (long id : ids) {
                LocalDate first = bySentence.getOrDefault(id, List.of()).stream()
                        .filter(r -> r.logDate().isAfter(date) && r.reps() >= reps)
                        .map(SpeakRow::logDate)
                        .min(LocalDate::compareTo)
                        .orElse(null);
                if (first == null) {
                    all = false;
                } else {
                    doneIds.add(id);
                    doneOn = doneOn == null || first.isAfter(doneOn) ? first : doneOn;
                }
            }
            out.add(new Makeup(day, date, rest, reps, date.plusDays(REPAIR_DAYS), List.copyOf(ids), List.copyOf(doneIds),
                    all ? doneOn : null, false));
        }
        return List.copyOf(out);
    }

    /**
     * 보충마다 실제로 스트릭을 지키는지(keepsStreak)를 채운다. 아직 기한 안인 보충을 모두 끝냈다고 가정하고 걸어 본 뒤,
     * 그 날짜 직전 스트릭이 0 보다 크면 지킨다. 끝낸 보충은 기한 안에 끝냈고 직전 스트릭이 0 보다 컸을 때 지켰다.
     */
    static List<Makeup> withStreakEffect(List<Makeup> makeups, LocalDate start, LocalDate today,
            Set<LocalDate> achieved, int totalDays) {
        Set<LocalDate> hoped = new HashSet<>();
        for (Makeup m : makeups) {
            if (m.repairs() || (!m.done() && !today.isAfter(m.deadline()))) {
                hoped.add(m.date());
            }
        }
        Map<LocalDate, Integer> before = walk(start, today, achieved, hoped, totalDays).streakBefore();
        List<Makeup> out = new ArrayList<>(makeups.size());
        for (Makeup m : makeups) {
            boolean inTime = m.done() ? m.repairs() : !today.isAfter(m.deadline());
            out.add(m.withKeepsStreak(inTime && before.getOrDefault(m.date(), 0) > 0));
        }
        return List.copyOf(out);
    }

    /** 기한 안에 보충을 끝내 스트릭이 이어지는 날짜. */
    static Set<LocalDate> repairedDates(List<Makeup> makeups) {
        Set<LocalDate> out = new HashSet<>();
        for (Makeup m : makeups) {
            if (m.repairs()) {
                out.add(m.date());
            }
        }
        return out;
    }

    static Walk walk(LocalDate start, LocalDate today, Set<LocalDate> achieved, int totalDays) {
        return walk(start, today, achieved, Set.of(), totalDays);
    }

    /**
     * 시작일부터 걷는다. 지난 날: 달성이면 +1, 기한 안에 보충했으면 유지(프리즈를 쓰지 않는다), 미달성이면
     * (스트릭 > 0 이고 그 30일 구간 프리즈가 남았을 때만) 프리즈로 유지, 아니면 0. 오늘은 달성했을 때만 +1 하고 끊지 않는다.
     * 프로그램 범위(시작일 + totalDays - 1) 밖의 날은 세지 않는다.
     */
    static Walk walk(LocalDate start, LocalDate today, Set<LocalDate> achieved, Set<LocalDate> repaired,
            int totalDays) {
        int[] freezes = new int[(totalDays + PERIOD_DAYS - 1) / PERIOD_DAYS];
        Arrays.fill(freezes, FREEZES_PER_PERIOD);

        int streak = 0;
        int longest = 0;
        int achievedDays = 0;
        boolean todayInProgram = false;
        List<Cell> cells = new ArrayList<>(totalDays);
        Map<LocalDate, Integer> streakBefore = new HashMap<>();
        for (int day = 1; day <= totalDays; day++) {
            LocalDate date = start.plusDays(day - 1L);
            int period = (day - 1) / PERIOD_DAYS;
            boolean done = achieved.contains(date);
            streakBefore.put(date, streak);
            CellStatus status;
            if (date.isBefore(today)) {
                if (done) {
                    streak++;
                    achievedDays++;
                    status = CellStatus.ACHIEVED;
                } else if (repaired.contains(date)) {
                    status = CellStatus.REPAIRED;
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
                achievedDays, List.copyOf(cells), Map.copyOf(streakBefore));
    }
}
