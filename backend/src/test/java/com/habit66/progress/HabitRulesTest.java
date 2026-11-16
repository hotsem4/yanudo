package com.habit66.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.habit66.common.LogicalClock;
import com.habit66.progress.DaySnapshot.Cell;
import com.habit66.progress.DaySnapshot.CellStatus;
import com.habit66.progress.DaySnapshot.Makeup;
import com.habit66.progress.DaySnapshot.RoundSummary;
import com.habit66.progress.HabitRules.Schedule;
import com.habit66.progress.ProgressFacts.AchievementRow;
import com.habit66.progress.HabitRules.ReviewPlan;
import com.habit66.progress.HabitRules.Walk;
import com.habit66.progress.ProgressFacts.SpeakRow;

class HabitRulesTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final int TOTAL = 66;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** Day 번호(1부터)로 달성한 날 집합을 만든다. */
    private static Set<LocalDate> achievedOn(int... days) {
        Set<LocalDate> dates = new HashSet<>();
        for (int day : days) {
            dates.add(START.plusDays(day - 1L));
        }
        return dates;
    }

    private static Set<LocalDate> achievedRange(int from, int to) {
        Set<LocalDate> dates = new HashSet<>();
        for (int day = from; day <= to; day++) {
            dates.add(START.plusDays(day - 1L));
        }
        return dates;
    }

    private static LocalDate day(int n) {
        return START.plusDays(n - 1L);
    }

    private static CellStatus status(Walk walk, int day) {
        return walk.cells().get(day - 1).status();
    }

    private static NavigableSet<Integer> days(Integer... registered) {
        return new TreeSet<>(List.of(registered));
    }

    // ── 스트릭 ────────────────────────────────────────────────

    @Test
    void firstDayBeforeStudyingHasNoStreakButOffersOne() {
        Walk walk = HabitRules.walk(START, day(1), Set.of(), TOTAL);

        assertThat(walk.streak().current()).isZero();
        assertThat(walk.streak().ifAchievedToday()).isEqualTo(1);
        assertThat(walk.streak().achievedToday()).isFalse();
        assertThat(walk.streak().freezesLeft()).isEqualTo(2);
        assertThat(walk.streak().freezesPerPeriod()).isEqualTo(2);
        assertThat(status(walk, 1)).isEqualTo(CellStatus.TODAY);
        assertThat(status(walk, 2)).isEqualTo(CellStatus.FUTURE);
    }

    @Test
    void consecutiveDaysCountAndTodayIsNotBrokenYet() {
        Walk walk = HabitRules.walk(START, day(3), achievedOn(1, 2), TOTAL);

        assertThat(walk.streak().current()).isEqualTo(2);
        assertThat(walk.streak().ifAchievedToday()).isEqualTo(3);
        assertThat(walk.streak().longest()).isEqualTo(2);
        assertThat(walk.achievedDays()).isEqualTo(2);
        assertThat(status(walk, 3)).isEqualTo(CellStatus.TODAY);
    }

    @Test
    void achievingTodayAddsOne() {
        Walk walk = HabitRules.walk(START, day(3), achievedOn(1, 2, 3), TOTAL);

        assertThat(walk.streak().current()).isEqualTo(3);
        assertThat(walk.streak().ifAchievedToday()).isEqualTo(3);
        assertThat(walk.streak().achievedToday()).isTrue();
        assertThat(status(walk, 3)).isEqualTo(CellStatus.ACHIEVED);
        assertThat(walk.achievedDays()).isEqualTo(3);
    }

    // ── 프리즈 ────────────────────────────────────────────────

    @Test
    void missedDayUsesFreezeAndKeepsStreakWithoutGrowing() {
        Walk walk = HabitRules.walk(START, day(4), achievedOn(1, 3), TOTAL);

        assertThat(status(walk, 2)).isEqualTo(CellStatus.FROZEN);
        assertThat(walk.streak().current()).isEqualTo(2);
        assertThat(walk.streak().freezesLeft()).isEqualTo(1);
    }

    @Test
    void freezeIsNotSpentWhenStreakIsZero() {
        Walk walk = HabitRules.walk(START, day(4), achievedOn(3), TOTAL);

        assertThat(status(walk, 1)).isEqualTo(CellStatus.MISSED);
        assertThat(status(walk, 2)).isEqualTo(CellStatus.MISSED);
        assertThat(walk.streak().current()).isEqualTo(1);
        assertThat(walk.streak().freezesLeft()).isEqualTo(2);
    }

    @Test
    void thirdMissInOnePeriodBreaksTheStreak() {
        Walk walk = HabitRules.walk(START, day(6), achievedOn(1, 5), TOTAL);

        assertThat(status(walk, 2)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(walk, 3)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(walk, 4)).isEqualTo(CellStatus.MISSED);
        assertThat(walk.streak().current()).isEqualTo(1);
        assertThat(walk.streak().longest()).isEqualTo(1);
        assertThat(walk.streak().freezesLeft()).isZero();
    }

    @Test
    void walkIsDeterministic() {
        Set<LocalDate> achieved = achievedOn(1, 2, 4, 7, 8, 9, 12);

        assertThat(HabitRules.walk(START, day(15), achieved, TOTAL))
                .isEqualTo(HabitRules.walk(START, day(15), achieved, TOTAL));
    }

    // ── 30일 구간 경계 ────────────────────────────────────────

    @Test
    void day30To31GivesFreshFreezesAndUnusedOnesDoNotCarryOver() {
        Set<LocalDate> achieved = achievedRange(1, 30);
        achieved.remove(day(29));
        achieved.remove(day(30));

        Walk onDay30 = HabitRules.walk(START, day(30), achieved, TOTAL);
        assertThat(onDay30.streak().freezesLeft()).isEqualTo(1);

        Walk onDay31 = HabitRules.walk(START, day(31), achieved, TOTAL);
        assertThat(status(onDay31, 29)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(onDay31, 30)).isEqualTo(CellStatus.FROZEN);
        assertThat(onDay31.streak().current()).isEqualTo(28);
        assertThat(onDay31.streak().freezesLeft()).isEqualTo(2);

        // 첫 구간에서 하나도 쓰지 않았어도 둘째 구간은 2개뿐이다.
        Set<LocalDate> perfectFirst = achievedRange(1, 30);
        Walk later = HabitRules.walk(START, day(35), perfectFirst, TOTAL);
        assertThat(status(later, 31)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(later, 32)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(later, 33)).isEqualTo(CellStatus.MISSED);
        assertThat(later.streak().current()).isZero();
        assertThat(later.streak().longest()).isEqualTo(30);
    }

    @Test
    void day60To61StartsTheThirdPeriod() {
        Set<LocalDate> achieved = achievedRange(1, 58);

        Walk onDay61 = HabitRules.walk(START, day(61), achieved, TOTAL);
        assertThat(status(onDay61, 59)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(onDay61, 60)).isEqualTo(CellStatus.FROZEN);
        assertThat(onDay61.streak().current()).isEqualTo(58);
        assertThat(onDay61.streak().freezesLeft()).isEqualTo(2);

        Walk onDay63 = HabitRules.walk(START, day(63), achieved, TOTAL);
        assertThat(status(onDay63, 61)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(onDay63, 62)).isEqualTo(CellStatus.FROZEN);
        assertThat(onDay63.streak().current()).isEqualTo(58);
        assertThat(onDay63.streak().freezesLeft()).isZero();
    }

    // ── 라운드: 66일은 끝이 아니라 한 바퀴 ─────────────────────

    @Test
    void dayKeepsCountingAfter66AndRoundsRollEvery66Days() {
        assertThat(HabitRules.currentDay(START, day(66))).isEqualTo(66);
        assertThat(HabitRules.currentDay(START, day(67))).isEqualTo(67);
        assertThat(HabitRules.currentDay(START, day(200))).isEqualTo(200);
        assertThat(HabitRules.round(66, TOTAL)).isEqualTo(1);
        assertThat(HabitRules.round(67, TOTAL)).isEqualTo(2);
        assertThat(HabitRules.roundDay(67, TOTAL)).isEqualTo(1);
        assertThat(HabitRules.roundDay(132, TOTAL)).isEqualTo(66);
        assertThat(HabitRules.round(133, TOTAL)).isEqualTo(3);
    }

    @Test
    void streakKeepsGrowingIntoTheNextRound() {
        Set<LocalDate> achieved = achievedRange(1, 67);

        Walk walk = HabitRules.walk(START, day(67), achieved, TOTAL);

        assertThat(walk.streak().current()).isEqualTo(67);
        assertThat(walk.achievedDays()).isEqualTo(1);
        assertThat(walk.cells()).hasSize(66);
        assertThat(walk.cells().get(0)).isEqualTo(new Cell(1, day(67), CellStatus.ACHIEVED));
        assertThat(walk.cells().get(1).status()).isEqualTo(CellStatus.FUTURE);
        assertThat(walk.pastRounds()).containsExactly(new RoundSummary(1, 66, 66));

        // 그 뒤에도 빼먹으면 프리즈(30일마다 2개)가 지키고, 다 쓰면 끊긴다.
        Walk later = HabitRules.walk(START, day(100), achievedRange(1, 97), TOTAL);
        assertThat(later.streak().current()).isEqualTo(97);
        assertThat(status(later, 98 - 66)).isEqualTo(CellStatus.FROZEN);
        assertThat(status(later, 99 - 66)).isEqualTo(CellStatus.FROZEN);
        Walk broken = HabitRules.walk(START, day(100), achievedRange(1, 90), TOTAL);
        assertThat(broken.streak().current()).isZero();
        assertThat(broken.streak().longest()).isEqualTo(90);
    }

    @Test
    void currentDayFollowsTheCalendarEvenWhenDaysAreSkipped() {
        assertThat(HabitRules.currentDay(START, START)).isEqualTo(1);
        assertThat(HabitRules.currentDay(START, day(10))).isEqualTo(10);
        assertThat(HabitRules.currentDay(START, START.minusDays(3))).isEqualTo(1);
    }

    @Test
    void heatmapAlwaysHas66CellsInOrder() {
        Walk walk = HabitRules.walk(START, day(5), achievedOn(1, 2), TOTAL);

        assertThat(walk.cells()).hasSize(66);
        assertThat(walk.cells().get(0)).isEqualTo(new Cell(1, START, CellStatus.ACHIEVED));
        assertThat(walk.cells().get(65).day()).isEqualTo(66);
        assertThat(walk.cells().get(65).date()).isEqualTo(START.plusDays(65));
        assertThat(walk.cells().get(65).status()).isEqualTo(CellStatus.FUTURE);
        assertThat(walk.pastRounds()).isEmpty();
    }

    // ── 레슨 일정(새 레슨·복습 순환)·게이트·복습 큐 ──────────────

    /** achieved 는 (그날의 Day 번호, 그날 끝낸 레슨 Day) 쌍. */
    private static Schedule schedule(int untilDay, List<int[]> achieved, Integer... registered) {
        List<AchievementRow> rows = achieved.stream().map(a -> new AchievementRow(day(a[0]), a[1])).toList();
        return HabitRules.schedule(START, day(untilDay), days(registered), rows);
    }

    @Test
    void eachDayStudiesItsOwnLessonWhileThereIsOne() {
        Schedule s = schedule(5, List.of(), 1, 2, 3, 4, 5);
        assertThat(s.lessonOn(day(3))).isEqualTo(3);
        assertThat(s.repeat(day(3))).isFalse();
        assertThat(schedule(2, List.of(), 3, 4).lessonOn(day(2))).isZero();
        assertThat(schedule(5, List.of()).lessonOn(day(5))).isZero();
    }

    @Test
    void withoutANewLessonTheOldestSeenLessonComesBack() {
        // Day 1~3 을 그날 달성했다. Day 4 는 새 레슨이 없어 마지막 달성이 가장 이른 Day 1 을 다시 한다.
        List<int[]> views = new java.util.ArrayList<>(List.of(new int[] {1, 1}, new int[] {2, 2}, new int[] {3, 3}));
        Schedule s = schedule(6, views, 1, 2, 3);
        assertThat(s.lessonOn(day(4))).isEqualTo(1);
        assertThat(s.repeat(day(4))).isTrue();
        // Day 4 를 빼먹었으면 Day 5 도 Day 1 이다.
        assertThat(s.lessonOn(day(5))).isEqualTo(1);

        views.add(new int[] {4, 1});
        Schedule studied = schedule(6, views, 1, 2, 3);
        assertThat(studied.lessonOn(day(5))).isEqualTo(2);
        // 달성한 날은 그때 끝낸 레슨으로 고정된다.
        assertThat(studied.lessonOn(day(4))).isEqualTo(1);
    }

    @Test
    void anAchievedDateKeepsItsLessonEvenIfContentIsAddedLater() {
        // Day 4 에 새 레슨이 없어 Day 1 을 다시 해 달성했다. 나중에 Day 4 레슨을 넣어도 그날은 Day 1 이다.
        List<int[]> achieved = List.of(new int[] {1, 1}, new int[] {2, 2}, new int[] {3, 3}, new int[] {4, 1});
        Schedule s = schedule(5, achieved, 1, 2, 3, 4);
        assertThat(s.lessonOn(day(4))).isEqualTo(1);
        assertThat(s.repeat(day(4))).isTrue();
        assertThat(s.lessonOn(day(5))).isEqualTo(4); // 오늘 Day 5 는 등록 안 됨 → 한 번도 달성 못 한 Day 4 먼저
    }

    @Test
    void lessonsNeverAchievedComeBackFirst() {
        // Day 2 를 빼먹었다(달성 없음). 콘텐츠가 Day 3 까지라 Day 4 에는 빼먹은 Day 2 를 다시 한다.
        Schedule s = schedule(4, List.of(new int[] {1, 1}, new int[] {3, 3}), 1, 2, 3);
        assertThat(s.lessonOn(day(4))).isEqualTo(2);
    }

    @Test
    void aGapInContentReviewsAnEarlierLessonInsteadOfRepeatingTheLastOne() {
        Schedule s = schedule(4, List.of(new int[] {1, 1}, new int[] {2, 2}), 1, 2, 4);
        assertThat(s.lessonOn(day(3))).isEqualTo(1);
        assertThat(s.lessonOn(day(4))).isEqualTo(4);
        assertThat(s.repeat(day(4))).isFalse();
    }

    @Test
    void day1HasNoGateAndPreviewsItself() {
        Schedule s = schedule(1, List.of(), 1, 2, 3);
        assertThat(HabitRules.gateDay(s, START, day(1))).isNull();
        assertThat(HabitRules.reviewPlan(s, START, day(1))).isEqualTo(new ReviewPlan(List.of(1), true));
    }

    @Test
    void gateIsYesterdaysLesson() {
        assertThat(HabitRules.gateDay(schedule(3, List.of(), 1, 2, 3), START, day(3))).isEqualTo(2);
        // Day 3 은 새 레슨이 없어 Day 1 을 다시 했다. 오늘(Day 4)의 게이트는 그 Day 1 이다.
        assertThat(HabitRules.gateDay(schedule(4, List.of(), 1, 2, 4), START, day(4))).isEqualTo(1);
        assertThat(HabitRules.gateDay(schedule(3, List.of(), 3, 5), START, day(3))).isNull();
        assertThat(HabitRules.gateDay(schedule(5, List.of()), START, day(5))).isNull();
    }

    @Test
    void reviewQueueTakesUpToFourRecentLessonsNewestFirst() {
        assertThat(HabitRules.reviewPlan(schedule(7, List.of(), 1, 2, 3, 4, 5, 6, 7), START, day(7)))
                .isEqualTo(new ReviewPlan(List.of(6, 5, 4, 3), false));
        assertThat(HabitRules.reviewPlan(schedule(3, List.of(), 1, 2, 3), START, day(3)))
                .isEqualTo(new ReviewPlan(List.of(2, 1), false));
        assertThat(HabitRules.reviewPlan(schedule(5, List.of()), START, day(5)))
                .isEqualTo(new ReviewPlan(List.of(), false));
    }

    // ── 완주 ──────────────────────────────────────────────────

    @Test
    void lessonCompletesOnlyWhenEverySentenceWasSeen() {
        List<Long> lesson = List.of(1L, 2L, 3L);

        assertThat(HabitRules.completes(lesson, Set.of(1L, 2L))).isFalse();
        assertThat(HabitRules.completes(lesson, Set.of(1L, 2L, 3L))).isTrue();
        assertThat(HabitRules.completes(lesson, Set.of(1L, 2L, 3L, 99L))).isTrue();
    }

    @Test
    void emptyLessonNeverCompletes() {
        assertThat(HabitRules.completes(List.of(), Set.of())).isFalse();
        assertThat(HabitRules.completes(List.of(), Set.of(1L, 2L))).isFalse();
    }

    @Test
    void recordedAchievementsSurviveContentChanges() {
        // 스트릭은 day_achievement 의 날짜만 본다. 레슨 문장이 나중에 늘어나 "지금 기준으로는 미완주"가 되어도
        // 이미 기록된 날은 그대로 달성이다.
        Set<LocalDate> recorded = achievedOn(1, 2);
        List<Long> lessonAfterEdit = List.of(1L, 2L, 3L, 4L);
        Set<Long> seenBackThen = Set.of(1L, 2L, 3L);
        assertThat(HabitRules.completes(lessonAfterEdit, seenBackThen)).isFalse();

        Walk walk = HabitRules.walk(START, day(3), recorded, TOTAL);
        assertThat(status(walk, 1)).isEqualTo(CellStatus.ACHIEVED);
        assertThat(status(walk, 2)).isEqualTo(CellStatus.ACHIEVED);
        assertThat(walk.streak().current()).isEqualTo(2);
    }

    // ── 03시 롤오버 ───────────────────────────────────────────

    @Test
    void studyBefore3amBelongsToThePreviousDay() {
        LogicalClock clock = new LogicalClock(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), SEOUL, 3);

        assertThat(clock.dateOf(seoul(2026, 11, 15, 2, 59))).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(clock.dateOf(seoul(2026, 11, 15, 3, 0))).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(clock.dateOf(seoul(2026, 11, 15, 23, 59))).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(clock.dateOf(seoul(2026, 11, 16, 1, 0))).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void todayBoundsRunFrom3amTo3am() {
        LogicalClock clock = new LogicalClock(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), SEOUL, 3);

        LogicalClock.Bounds bounds = clock.bounds(LocalDate.of(2026, 11, 15));

        assertThat(bounds.from()).isEqualTo(seoul(2026, 11, 15, 3, 0));
        assertThat(bounds.until()).isEqualTo(seoul(2026, 11, 16, 3, 0));
        assertThat(clock.dateOf(bounds.from())).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(clock.dateOf(bounds.until() - 1)).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(clock.dateOf(bounds.until())).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void rolloverMovesTheDayNumberAt3am() {
        LogicalClock clock = new LogicalClock(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), SEOUL, 3);

        LocalDate before = clock.dateOf(seoul(2026, 11, 14, 2, 30));
        LocalDate after = clock.dateOf(seoul(2026, 11, 14, 3, 30));

        assertThat(HabitRules.currentDay(START, before)).isEqualTo(1);
        assertThat(HabitRules.currentDay(START, after)).isEqualTo(2);
    }


    // ── 보충과 스트릭 회복 ────────────────────────────────────

    /** Day 1~3 레슨, 문장 id 는 Day*10 + 0..1 (두 문장씩). */
    private static final Map<Integer, List<Long>> LESSONS = Map.of(1, List.of(10L, 11L), 2, List.of(20L, 21L),
            3, List.of(30L, 31L));

    private static Schedule plan(Map<Integer, List<Long>> lessons, int untilDay) {
        return HabitRules.schedule(START, day(untilDay), new TreeSet<>(lessons.keySet()), List.of());
    }

    private static List<SpeakRow> spoke(int onDay, int reps, long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new SpeakRow(id, day(onDay), reps)).toList();
    }

    @Test
    void makeupWithinDeadlineRepairsTheMissedDayWithoutAFreeze() {
        List<SpeakRow> speaks = spoke(4, 5, 20L, 21L); // Day 2 를 Day 4 에 보충
        List<Makeup> makeups = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(4), plan(LESSONS, 4), LESSONS, achievedOn(1, 3), Set.of(), speaks),
                START, day(4), achievedOn(1, 3), TOTAL);

        assertThat(makeups).hasSize(1);
        Makeup m = makeups.get(0);
        assertThat(m.day()).isEqualTo(2);
        assertThat(m.reps()).isEqualTo(5);
        assertThat(m.doneOn()).isEqualTo(day(4));
        assertThat(m.repairs()).isTrue();
        Walk walk = HabitRules.walk(START, day(4), achievedOn(1, 3), HabitRules.repairedDates(makeups), TOTAL);
        assertThat(status(walk, 2)).isEqualTo(CellStatus.REPAIRED);
        assertThat(walk.streak().current()).isEqualTo(2);
        assertThat(walk.streak().freezesLeft()).isEqualTo(2);
    }

    @Test
    void makeupCountsOnlyEnoughRepsAfterTheDayAndBeforeTheDeadline() {
        // 그날 한 연습, 4번만 한 연습은 세지 않는다. 기한(Day 2 + 3일 = Day 5)을 넘겨 끝내면 열리기만 하고 회복은 없다.
        List<SpeakRow> speaks = new java.util.ArrayList<>(spoke(2, 5, 20L, 21L));
        speaks.addAll(spoke(3, 4, 20L));
        speaks.addAll(spoke(6, 5, 20L, 21L));
        Makeup m = HabitRules.makeups(START, day(7), plan(LESSONS, 7), LESSONS, achievedOn(1, 3), Set.of(), speaks).stream()
                .filter(x -> x.day() == 2).findFirst().orElseThrow();

        assertThat(m.done()).isTrue();
        assertThat(m.doneOn()).isEqualTo(day(6));
        assertThat(m.repairs()).isFalse();
    }

    @Test
    void restDayNeedsOneRepAndIsLimitedPerPeriod() {
        Makeup m = HabitRules.makeups(START, day(3), plan(LESSONS, 3), LESSONS, achievedOn(1), Set.of(day(2)),
                spoke(3, 1, 20L, 21L)).get(0);
        assertThat(m.rest()).isTrue();
        assertThat(m.reps()).isEqualTo(1);
        assertThat(m.repairs()).isTrue();

        Set<LocalDate> rest = Set.of(day(2), day(5), day(30), day(31), day(70), START.minusDays(3));
        assertThat(HabitRules.restDaysUsed(START, day(10), rest, Set.of())).isEqualTo(3);
        assertThat(HabitRules.restDaysUsed(START, day(31), rest, Set.of())).isEqualTo(1);
        // 알린 뒤 그날 달성했으면 쓰지 않은 것으로 친다. 시작일 전 기록은 세지 않는다. 66일 뒤에도 구간은 계속된다.
        assertThat(HabitRules.restDaysUsed(START, day(10), rest, achievedOn(5))).isEqualTo(2);
        assertThat(HabitRules.restDaysUsed(START, day(66), rest, Set.of())).isEqualTo(1);
    }

    @Test
    void makeupKeepsTheStreakOnlyWhenThereIsAStreakToKeep() {
        // Day 1 달성, Day 2·3 빼먹음, 오늘 Day 4. 둘 다 보충하면 이어지므로 둘 다 지킨다.
        List<Makeup> both = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(4), plan(LESSONS, 4), LESSONS, achievedOn(1), Set.of(), List.of()),
                START, day(4), achievedOn(1), TOTAL);
        assertThat(both).extracting(Makeup::keepsStreak).containsExactly(true, true);

        // 처음부터 안 했으면(스트릭 0) 보충해도 지킬 것이 없다. Day 를 열기만 한다.
        List<Makeup> none = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(4), plan(LESSONS, 4), LESSONS, Set.of(), Set.of(), List.of()),
                START, day(4), Set.of(), TOTAL);
        assertThat(none).extracting(Makeup::keepsStreak).containsOnly(false);

        // 기한이 지난 앞날(Day 2, 기한 Day 5)이 이미 끊었으면(프리즈 없음 가정 대신 스트릭 0) 뒤의 보충도 지킬 것이 없다.
        List<Makeup> broken = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(8), plan(Map.of(2, List.of(20L), 3, List.of(30L)), 8), Map.of(2, List.of(20L), 3, List.of(30L)), Set.of(), Set.of(),
                        List.of()),
                START, day(8), Set.of(), TOTAL);
        assertThat(broken).extracting(Makeup::keepsStreak).containsOnly(false);
    }

    /** Day 1~12 레슨, 문장 하나씩(id = Day*10). */
    private static final Map<Integer, List<Long>> TWELVE = java.util.stream.IntStream.rangeClosed(1, 12).boxed()
            .collect(java.util.stream.Collectors.toMap(d -> d, d -> List.of(d * 10L)));

    private static List<Makeup> effects(int today, Set<LocalDate> achieved, List<SpeakRow> speaks) {
        return HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(today), plan(TWELVE, today), TWELVE, achieved, Set.of(), speaks),
                START, day(today), achieved, TOTAL);
    }

    private static Makeup on(List<Makeup> makeups, int day) {
        return makeups.stream().filter(m -> m.date().equals(day(day))).findFirst().orElseThrow();
    }

    @Test
    void laterMakeupDoesNotPaintRepairedWhileEarlierMissedDaysAreOpen() {
        // Day 1·2·5·7 달성(3·4 프리즈, 6 끊김), 8·9·10 빼먹음, 오늘 Day 11 에 Day 10 만 보충했다.
        Set<LocalDate> achieved = achievedOn(1, 2, 5, 7);
        List<Makeup> makeups = effects(11, achieved, spoke(11, 5, 100L));

        Makeup tenth = on(makeups, 10);
        assertThat(tenth.repairs()).isTrue();
        assertThat(tenth.keepsStreak()).isFalse(); // 앞날(8·9)이 끊겨 있어 아직 지킬 스트릭이 없다
        Walk walk = HabitRules.walk(START, day(11), achieved, HabitRules.repairedDates(makeups), TOTAL);
        assertThat(status(walk, 10)).isEqualTo(CellStatus.MISSED);
        assertThat(walk.streak().current()).isZero();

        // Day 8 하나로는 9 가 끊어 모자라고, 8·9 를 다 하면(10 은 이미 함) 연속 1일로 이어진다.
        assertThat(on(makeups, 8).effect()).isEqualTo(DaySnapshot.MakeupEffect.WITH_OTHERS);
        assertThat(on(makeups, 8).streakIfDone()).isEqualTo(1);
        // Day 9 는 Day 8 을 먼저 해야 의미가 있다.
        assertThat(on(makeups, 9).effect()).isEqualTo(DaySnapshot.MakeupEffect.AFTER_EARLIER);

        // 8·9 까지 끝내면 10 도 지킨 것으로 칠해진다.
        List<SpeakRow> all = new java.util.ArrayList<>(spoke(11, 5, 100L));
        all.addAll(spoke(11, 5, 80L, 90L));
        List<Makeup> done = effects(11, achieved, all);
        Walk after = HabitRules.walk(START, day(11), achieved, HabitRules.repairedDates(done), TOTAL);
        assertThat(status(after, 8)).isEqualTo(CellStatus.REPAIRED);
        assertThat(status(after, 10)).isEqualTo(CellStatus.REPAIRED);
        assertThat(after.streak().current()).isEqualTo(1);
    }

    @Test
    void makeupEffectTellsWhatThisOneMakeupDoes() {
        // Day 1 달성, 2·3 은 프리즈, 4 에서 끊김, 오늘 Day 5.
        List<Makeup> makeups = effects(5, achievedOn(1), List.of());
        // 끊긴 Day 4 를 보충하면 연속 1일이 이어진다.
        assertThat(on(makeups, 4).effect()).isEqualTo(DaySnapshot.MakeupEffect.STREAK);
        assertThat(on(makeups, 4).streakIfDone()).isEqualTo(1);
        // 프리즈로 넘긴 Day 2 를 보충하면 프리즈가 돌아오고, 그 프리즈가 Day 4 를 지켜 연속도 이어진다.
        assertThat(on(makeups, 2).effect()).isEqualTo(DaySnapshot.MakeupEffect.STREAK);

        // Day 1·3 달성, 2 는 프리즈, 오늘 Day 4: Day 2 보충은 연속은 그대로(이미 지켜짐)이고 프리즈만 돌아온다.
        List<Makeup> frozen = effects(4, achievedOn(1, 3), List.of());
        assertThat(on(frozen, 2).effect()).isEqualTo(DaySnapshot.MakeupEffect.FREEZE);
    }

    @Test
    void frozenDayBeforeABreakSaysWithOthersNotFreeze() {
        // Day 1·2 달성, 3·4 프리즈, 5 끊김, 6 빼먹음, 오늘 Day 7. Day 4 만 끝내면 돌아온 프리즈가 Day 5 에 다시 쓰여 효과가 없다.
        List<Makeup> makeups = effects(7, achievedOn(1, 2), List.of());
        assertThat(on(makeups, 4).effect()).isEqualTo(DaySnapshot.MakeupEffect.WITH_OTHERS);
        assertThat(on(makeups, 4).streakIfDone()).isEqualTo(2);
        assertThat(on(makeups, 6).effect()).isEqualTo(DaySnapshot.MakeupEffect.AFTER_EARLIER);
    }

    @Test
    void oneMakeupFillsConsecutiveMissesOfTheSameLesson() {
        // 레슨 1·2 만 있다. Day 1·2 달성, Day 3~6 은 복습 순환으로 모두 레슨 1, 오늘 Day 7.
        Map<Integer, List<Long>> two = Map.of(1, List.of(10L), 2, List.of(20L));
        Set<LocalDate> achieved = achievedOn(1, 2);
        Schedule schedule = HabitRules.schedule(START, day(7), new TreeSet<>(two.keySet()),
                List.of(new AchievementRow(day(1), 1), new AchievementRow(day(2), 2)));
        List<Makeup> makeups = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(7), schedule, two, achieved, Set.of(), List.of()),
                START, day(7), achieved, TOTAL);
        assertThat(makeups).allMatch(m -> m.day() == 1);
        // 화면에 나오는 가장 이른 기한 안 줄(Day 4): 레슨 1 을 한 번 보충하면 4·5·6 이 모두 이어져 연속 2일.
        Makeup shown = makeups.stream().filter(m -> m.keepsStreak()).findFirst().orElseThrow();
        assertThat(shown.effect()).isEqualTo(DaySnapshot.MakeupEffect.STREAK);
        assertThat(shown.streakIfDone()).isEqualTo(2);
    }

    @Test
    void sentencesAddedAfterTheMissedDayDoNotUndoAFinishedMakeup() {
        // Day 2(문장 20·21)를 Day 3 에 보충해 끝냈다. 뒤에 Day 2 에 문장 22 를 추가했다(Day 5 에 넣음).
        Map<Integer, List<Long>> lessons = Map.of(1, List.of(10L, 11L), 2, List.of(20L, 21L, 22L), 3, List.of(30L, 31L));
        Map<Long, LocalDate> addedOn = Map.of(10L, START, 11L, START, 20L, START, 21L, START, 22L, day(5),
                30L, START, 31L, START);
        Makeup m = HabitRules.makeups(START, day(6), plan(lessons, 6), lessons, achievedOn(1, 3), Set.of(),
                spoke(3, 5, 20L, 21L), addedOn).stream().filter(x -> x.day() == 2).findFirst().orElseThrow();
        assertThat(m.sentenceIds()).containsExactly(20L, 21L);
        assertThat(m.repairs()).isTrue();
    }

    @Test
    void makeupDeadlineIsInclusive() {
        Makeup onDeadline = HabitRules.makeups(START, day(6), plan(LESSONS, 6), LESSONS, achievedOn(1, 3), Set.of(),
                spoke(5, 5, 20L, 21L)).get(0); // Day 2 의 기한은 Day 5
        assertThat(onDeadline.repairs()).isTrue();
        Makeup late = HabitRules.makeups(START, day(7), plan(LESSONS, 7), LESSONS, achievedOn(1, 3), Set.of(),
                spoke(6, 5, 20L, 21L)).get(0);
        assertThat(late.repairs()).isFalse();
    }

    @Test
    void todayAndAchievedDaysNeedNoMakeup() {
        assertThat(HabitRules.makeups(START, day(2), plan(LESSONS, 2), LESSONS, achievedOn(1), Set.of(), List.of())).isEmpty();
    }

    private static long seoul(int year, int month, int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute).atZone(SEOUL).toInstant().toEpochMilli();
    }
}
