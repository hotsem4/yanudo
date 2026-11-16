package com.habit66.progress;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import com.habit66.progress.DaySnapshot.Cell;
import com.habit66.progress.DaySnapshot.CellStatus;
import com.habit66.progress.DaySnapshot.Makeup;
import com.habit66.progress.DaySnapshot.MakeupEffect;
import com.habit66.progress.DaySnapshot.RoundSummary;
import com.habit66.progress.DaySnapshot.Streak;
import com.habit66.progress.ProgressFacts.AchievementRow;
import com.habit66.progress.ProgressFacts.SpeakRow;

/**
 * 하루 상태의 규칙 전부. 순수 함수뿐이고 아무것도 저장하지 않는다(매 요청 다시 계산한다).
 *
 * <p>66일은 끝이 아니라 한 라운드의 길이다(습관이 붙는 최소 기간). Day 는 시작일부터 끝없이 늘고, 연속 기록·프리즈·보충도
 * 계속된다. 그 Day 의 새 레슨이 없으면 지금까지 배운 레슨 중 가장 오래 안 본 Day 를 다시 한다(복습 순환).
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

    /**
     * @param achievedDays 이번 라운드에서 달성한 날 수
     * @param cells        이번 라운드의 칸(roundDays 개, day 는 라운드 안의 Day 1~roundDays)
     * @param streakBefore 날짜마다 그날을 처리하기 직전의 스트릭(보충이 스트릭을 지키는지 판단할 때 쓴다)
     * @param pastRounds   끝난 라운드마다 달성한 날 수(1라운드부터)
     */
    record Walk(Streak streak, int achievedDays, List<Cell> cells, Map<LocalDate, Integer> streakBefore,
            List<RoundSummary> pastRounds) {
    }

    /**
     * 날짜마다 공부할 레슨. 순서대로:
     * <ol>
     * <li>그 날짜에 달성했으면 그때 끝낸 레슨(달성한 날은 고정된다. 늦게 온 기록·임포트·시작일 변경에도 흔들리지 않는다).
     * <li>그 Day 의 레슨이 등록돼 있으면 그것.
     * <li>없으면(콘텐츠를 다 썼거나 아직 안 넣었으면) 그 Day 보다 앞 레슨 중 마지막으로 달성한 날이 가장 이른 것(복습 순환).
     * 한 번도 달성하지 못한 레슨이 먼저, 같으면 앞 Day. 그 날짜 전날까지의 달성만 보므로 그날 안에서는 바뀌지 않고,
     * 빼먹으면 같은 레슨이 다음 날에도 남는다. 문장 몇 개만 보거나 복습 탭에서 본 것은 순서를 밀지 않는다.
     * </ol>
     *
     * @param lessonByDate 시작일부터 until 까지 날짜 → 레슨 Day(배울 레슨이 없으면 0)
     * @param repeatDates  그 Day 의 레슨이 아닌 지난 레슨을 다시 하는 날짜
     */
    record Schedule(Map<LocalDate, Integer> lessonByDate, Set<LocalDate> repeatDates) {

        int lessonOn(LocalDate date) {
            return lessonByDate.getOrDefault(date, 0);
        }

        boolean repeat(LocalDate date) {
            return repeatDates.contains(date);
        }
    }

    private HabitRules() {
    }

    /** 시작일부터 지난 날 + 1(1 이상). 하루를 빠져도 달력대로 넘어가고, 66일 뒤에도 계속 는다. */
    static int currentDay(LocalDate start, LocalDate date) {
        return (int) Math.max(1, ChronoUnit.DAYS.between(start, date) + 1);
    }

    /** 라운드(1부터). roundDays 일마다 하나. */
    static int round(int currentDay, int roundDays) {
        return (currentDay - 1) / roundDays + 1;
    }

    /** 라운드 안의 Day(1~roundDays). */
    static int roundDay(int currentDay, int roundDays) {
        return (currentDay - 1) % roundDays + 1;
    }

    /** 시작일부터 until 까지 날짜마다 레슨을 정한다(Schedule 참고). */
    static Schedule schedule(LocalDate start, LocalDate until, NavigableSet<Integer> days,
            List<AchievementRow> achievements) {
        Map<LocalDate, Integer> byDate = new HashMap<>();
        Set<LocalDate> repeats = new HashSet<>();
        if (days.isEmpty() || until.isBefore(start)) {
            return new Schedule(byDate, repeats);
        }
        Map<LocalDate, Integer> achievedLesson = new HashMap<>();
        for (AchievementRow a : achievements) {
            achievedLesson.put(a.date(), a.lessonDay());
        }
        List<AchievementRow> sorted = new ArrayList<>(achievements);
        sorted.sort(Comparator.comparing(AchievementRow::date));
        Map<Integer, LocalDate> lastAchieved = new HashMap<>();
        int next = 0;
        for (LocalDate date = start; !date.isAfter(until); date = date.plusDays(1)) {
            while (next < sorted.size() && sorted.get(next).date().isBefore(date)) {
                AchievementRow a = sorted.get(next++);
                lastAchieved.merge(a.lessonDay(), a.date(), (x, y) -> x.isAfter(y) ? x : y);
            }
            int cd = currentDay(start, date);
            Integer fixed = achievedLesson.get(date);
            Integer lesson = null;
            if (fixed != null && days.contains(fixed)) {
                lesson = fixed;
            } else if (days.contains(cd)) {
                lesson = cd;
            } else {
                for (int day : days.headSet(cd, false)) {
                    if (lesson == null || older(lastAchieved.get(day), lastAchieved.get(lesson))) {
                        lesson = day;
                    }
                }
            }
            if (lesson != null) {
                byDate.put(date, lesson);
                if (lesson != cd) {
                    repeats.add(date);
                }
            }
        }
        return new Schedule(byDate, repeats);
    }

    /** a 가 b 보다 오래됐는가(한 번도 없으면 가장 오래). 같으면 false(앞 Day 를 유지한다). */
    private static boolean older(LocalDate a, LocalDate b) {
        if (a == null) {
            return b != null;
        }
        return b != null && a.isBefore(b);
    }

    /** "어제 레슨" = 어제 공부할 레슨. 오늘이 첫날이거나 오늘 레슨과 같으면 게이트 없음(null). */
    static Integer gateDay(Schedule schedule, LocalDate start, LocalDate today) {
        if (!today.isAfter(start)) {
            return null;
        }
        int yesterday = schedule.lessonOn(today.minusDays(1));
        return yesterday <= 0 || yesterday == schedule.lessonOn(today) ? null : yesterday;
    }

    /**
     * 복습 큐의 일차: 어제부터 거꾸로 공부할 레슨이었던 것 최대 4개(겹치면 한 번, 오늘 레슨은 빼고), 최근 것 먼저.
     * 앞날이 없으면(첫날) 오늘 레슨 미리보기.
     */
    static ReviewPlan reviewPlan(Schedule schedule, LocalDate start, LocalDate today) {
        int todayLesson = schedule.lessonOn(today);
        if (todayLesson <= 0) {
            return new ReviewPlan(List.of(), false);
        }
        Set<Integer> picked = new LinkedHashSet<>();
        for (LocalDate d = today.minusDays(1); !d.isBefore(start) && picked.size() < REVIEW_DAYS; d = d.minusDays(1)) {
            int day = schedule.lessonOn(d);
            if (day > 0 && day != todayLesson) {
                picked.add(day);
            }
        }
        return picked.isEmpty() ? new ReviewPlan(List.of(todayLesson), true) : new ReviewPlan(List.copyOf(picked), false);
    }

    /** 레슨 문장을 전부 봤는가. 빈 레슨은 완주로 치지 않는다. */
    static boolean completes(Collection<Long> lessonSentenceIds, Set<Long> seenIds) {
        return !lessonSentenceIds.isEmpty() && seenIds.containsAll(lessonSentenceIds);
    }

    /** 그 날짜가 속한 30일 구간(0부터). 프리즈와 "오늘은 어려워요" 횟수가 구간마다 새로 생긴다. */
    static int period(LocalDate start, LocalDate date) {
        return (currentDay(start, date) - 1) / PERIOD_DAYS;
    }

    /**
     * 그 날짜의 구간에서 이미 쓴 "오늘은 어려워요" 횟수. 알린 뒤 그날 달성한 날은 쓰지 않은 것으로 친다.
     * 시작일 전 날짜의 기록은 세지 않는다(시작일을 옮겨도 첫 구간을 잠식하지 않게).
     */
    static int restDaysUsed(LocalDate start, LocalDate date, Collection<LocalDate> restDays, Set<LocalDate> achieved) {
        int period = period(start, date);
        return (int) restDays.stream()
                .filter(d -> !d.isBefore(start) && !achieved.contains(d))
                .filter(d -> period(start, d) == period)
                .count();
    }

    /**
     * 지난 날 중 달성하지 못한 날마다 보충 상태를 만든다(날짜 순). 그 날짜의 레슨은 일정(schedule)이 정한 것이다.
     * 보충은 그 날짜 다음 날부터 한 연습만 센다(그날 안에는 그냥 오늘 공부를 하면 된다).
     *
     * @param lessons Day → 문장 id(등록된 레슨만)
     */
    static List<Makeup> makeups(LocalDate start, LocalDate today, Schedule schedule, Map<Integer, List<Long>> lessons,
            Set<LocalDate> achieved, Set<LocalDate> restDays, List<SpeakRow> speaks) {
        Map<Long, List<SpeakRow>> bySentence = new HashMap<>();
        for (SpeakRow row : speaks) {
            bySentence.computeIfAbsent(row.sentenceId(), k -> new ArrayList<>()).add(row);
        }
        List<Makeup> out = new ArrayList<>();
        for (LocalDate date = start; date.isBefore(today); date = date.plusDays(1)) {
            int day = schedule.lessonOn(date);
            List<Long> ids = lessons.getOrDefault(day, List.of());
            if (day <= 0 || ids.isEmpty() || achieved.contains(date)) {
                continue;
            }
            LocalDate missed = date;
            boolean rest = restDays.contains(date);
            int reps = rest ? MAKEUP_REPS_REST : MAKEUP_REPS;
            List<Long> doneIds = new ArrayList<>();
            LocalDate doneOn = null;
            boolean all = true;
            for (long id : ids) {
                LocalDate first = bySentence.getOrDefault(id, List.of()).stream()
                        .filter(r -> r.logDate().isAfter(missed) && r.reps() >= reps)
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
     * 보충마다 스트릭에 미치는 효과를 채운다.
     * <ul>
     * <li>끝낸 보충: 실제 기록만으로 판정한다. 기한 안에 끝냈고 그 날짜 직전 스트릭이 0 보다 컸으면 지켰다(keepsStreak).
     * 앞날 보충을 끝내야 비로소 직전 스트릭이 생기는 경우가 있으므로, 지킨 날짜 집합이 더 줄지 않을 때까지 다시 걷는다
     * (날짜를 빼면 뒤 스트릭은 줄기만 하므로 끝난다). 앞날을 아직 안 했는데 뒷날만 "지킴"으로 칠했다가 앞날 기한이
     * 지나 "놓침"으로 되돌아가는 일을 막는다.
     * <li>아직 안 끝낸 기한 안 보충: 기한 안의 다른 보충도 끝낸다고 보고 직전 스트릭이 있으면 keepsStreak(목록에 남긴다).
     * 그리고 지금 이것만 끝냈을 때의 효과(effect)를 실제로 걸어 정한다: 끊긴 연속이 이어지면 STREAK, 그날 쓴 프리즈만
     * 돌아오면 FREEZE, 앞 보충을 먼저 해야 하면 AFTER_EARLIER.
     * </ul>
     */
    static List<Makeup> withStreakEffect(List<Makeup> makeups, LocalDate start, LocalDate today,
            Set<LocalDate> achieved, int roundDays) {
        // 1) 끝낸 보충 중 실제로 지킨 날짜(고정점)
        Set<LocalDate> doneInTime = new HashSet<>();
        for (Makeup m : makeups) {
            if (m.repairs()) {
                doneInTime.add(m.date());
            }
        }
        Set<LocalDate> kept = new HashSet<>(doneInTime);
        Walk actual = walk(start, today, achieved, kept, roundDays);
        while (true) {
            Map<LocalDate, Integer> before = actual.streakBefore();
            Set<LocalDate> next = new HashSet<>();
            for (LocalDate d : kept) {
                if (before.getOrDefault(d, 0) > 0) {
                    next.add(d);
                }
            }
            if (next.equals(kept)) {
                break;
            }
            kept = next;
            actual = walk(start, today, achieved, kept, roundDays);
        }

        // 2) 아직 안 끝낸 기한 안 보충을 모두 끝낸다고 볼 때(목록에 남길지)
        // 기한 안에 끝냈지만 앞날 때문에 아직 지키지 못한 보충도, 앞날을 끝내면 지키게 되므로 넣는다.
        Set<LocalDate> hoped = new HashSet<>();
        for (Makeup m : makeups) {
            if (m.repairs() || (!m.done() && !today.isAfter(m.deadline()))) {
                hoped.add(m.date());
            }
        }
        Walk hopedWalk = walk(start, today, achieved, hoped, roundDays);
        Map<LocalDate, Integer> hopedBefore = hopedWalk.streakBefore();
        Map<LocalDate, Integer> actualBefore = actual.streakBefore();

        List<Makeup> out = new ArrayList<>(makeups.size());
        for (Makeup m : makeups) {
            if (m.done()) {
                out.add(m.withEffect(kept.contains(m.date()), MakeupEffect.NONE, 0));
                continue;
            }
            boolean inTime = !today.isAfter(m.deadline());
            boolean keeps = inTime && hopedBefore.getOrDefault(m.date(), 0) > 0;
            if (!keeps) {
                out.add(m.withEffect(false, MakeupEffect.NONE, 0));
                continue;
            }
            // 3) 지금 이것만 끝내면
            int beforeNow = actualBefore.getOrDefault(m.date(), 0);
            if (beforeNow <= 0) {
                out.add(m.withEffect(true, MakeupEffect.AFTER_EARLIER, 0));
                continue;
            }
            // 이미 끝냈지만 앞날 때문에 못 지킨 보충은 이것으로 이어질 수 있으니 함께 넣는다.
            Set<LocalDate> withThis = new HashSet<>(doneInTime);
            withThis.add(m.date());
            int streakIfDone = walk(start, today, achieved, withThis, roundDays).streak().current();
            // 달성도 보충도 아닌 날인데 다음 날 직전 스트릭이 그대로면 프리즈로 넘긴 날이다(끊겼으면 0).
            boolean frozen = actualBefore.getOrDefault(m.date().plusDays(1), 0) == beforeNow;
            int now = actual.streak().current();
            if (streakIfDone > now) {
                out.add(m.withEffect(true, MakeupEffect.STREAK, streakIfDone));
            } else if (frozen) {
                out.add(m.withEffect(true, MakeupEffect.FREEZE, 0));
            } else if (hopedWalk.streak().current() > now) {
                // 이것 하나로는 모자라고 뒤의 보충까지 끝내야 이어진다(빼먹은 날이 연달아 있을 때).
                out.add(m.withEffect(true, MakeupEffect.WITH_OTHERS, hopedWalk.streak().current()));
            } else {
                out.add(m.withEffect(true, MakeupEffect.NONE, 0));
            }
        }
        return List.copyOf(out);
    }

    /** 기한 안에 보충을 끝내 스트릭이 이어지는 날짜(지킬 스트릭이 있던 날만, withStreakEffect 뒤에 쓴다). */
    static Set<LocalDate> repairedDates(List<Makeup> makeups) {
        Set<LocalDate> out = new HashSet<>();
        for (Makeup m : makeups) {
            if (m.repairs() && m.keepsStreak()) {
                out.add(m.date());
            }
        }
        return out;
    }

    static Walk walk(LocalDate start, LocalDate today, Set<LocalDate> achieved, int roundDays) {
        return walk(start, today, achieved, Set.of(), roundDays);
    }

    /**
     * 시작일부터 오늘까지 걷는다. 지난 날: 달성이면 +1, 기한 안에 보충했으면 유지(프리즈를 쓰지 않는다), 미달성이면
     * (스트릭 > 0 이고 그 30일 구간 프리즈가 남았을 때만) 프리즈로 유지, 아니면 0. 오늘은 달성했을 때만 +1 하고 끊지 않는다.
     * 칸은 오늘이 속한 라운드의 roundDays 개만 만든다(뒤는 FUTURE). 시작일 전이면 첫 라운드를 모두 FUTURE 로 둔다.
     */
    static Walk walk(LocalDate start, LocalDate today, Set<LocalDate> achieved, Set<LocalDate> repaired,
            int roundDays) {
        Map<Integer, Integer> freezes = new HashMap<>();
        int round = round(currentDay(start, today), roundDays);
        LocalDate roundStart = start.plusDays((long) (round - 1) * roundDays);

        int streak = 0;
        int longest = 0;
        int roundAchieved = 0;
        int[] pastAchieved = new int[round - 1];
        List<Cell> cells = new ArrayList<>(roundDays);
        Map<LocalDate, Integer> streakBefore = new HashMap<>();
        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            int period = period(start, date);
            int dateRound = round(currentDay(start, date), roundDays);
            boolean done = achieved.contains(date);
            streakBefore.put(date, streak);
            CellStatus status;
            if (date.isBefore(today)) {
                if (done) {
                    streak++;
                    status = CellStatus.ACHIEVED;
                } else if (repaired.contains(date)) {
                    status = CellStatus.REPAIRED;
                } else if (streak > 0 && freezes.getOrDefault(period, FREEZES_PER_PERIOD) > 0) {
                    freezes.merge(period, FREEZES_PER_PERIOD - 1, (left, x) -> left - 1);
                    status = CellStatus.FROZEN;
                } else {
                    streak = 0;
                    status = CellStatus.MISSED;
                }
            } else if (done) {
                streak++;
                status = CellStatus.ACHIEVED;
            } else {
                status = CellStatus.TODAY;
            }
            if (done) {
                if (dateRound == round) {
                    roundAchieved++;
                } else {
                    pastAchieved[dateRound - 1]++;
                }
            }
            longest = Math.max(longest, streak);
            if (dateRound == round) {
                cells.add(new Cell(roundDay(currentDay(start, date), roundDays), date, status));
            }
        }
        while (cells.size() < roundDays) {
            LocalDate date = roundStart.plusDays(cells.size());
            cells.add(new Cell(cells.size() + 1, date, today.isBefore(start) || date.isAfter(today)
                    ? CellStatus.FUTURE : CellStatus.TODAY));
        }

        boolean achievedToday = achieved.contains(today);
        int ifAchievedToday = achievedToday ? streak : streak + 1;
        int freezesLeft = freezes.getOrDefault(period(start, today), FREEZES_PER_PERIOD);
        List<RoundSummary> past = new ArrayList<>(round - 1);
        for (int r = 1; r < round; r++) {
            past.add(new RoundSummary(r, pastAchieved[r - 1], roundDays));
        }
        return new Walk(new Streak(streak, ifAchievedToday, longest, achievedToday, freezesLeft, FREEZES_PER_PERIOD),
                roundAchieved, List.copyOf(cells), Map.copyOf(streakBefore), List.copyOf(past));
    }
}
