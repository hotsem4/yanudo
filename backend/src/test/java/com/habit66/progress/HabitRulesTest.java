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
    void day60To61StartsTheLastPeriod() {
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

    // ── 프로그램 범위 ─────────────────────────────────────────

    @Test
    void day66To67FixesCurrentDayAndCompletesTheProgram() {
        assertThat(HabitRules.currentDay(START, day(66), TOTAL)).isEqualTo(66);
        assertThat(HabitRules.programComplete(START, day(66), TOTAL)).isFalse();

        assertThat(HabitRules.currentDay(START, day(67), TOTAL)).isEqualTo(66);
        assertThat(HabitRules.programComplete(START, day(67), TOTAL)).isTrue();
        assertThat(HabitRules.currentDay(START, day(200), TOTAL)).isEqualTo(66);
    }

    @Test
    void afterDay66TheStreakIsFrozenAtItsFinalValue() {
        Set<LocalDate> achieved = achievedRange(1, 66);
        achieved.add(day(67));

        Walk walk = HabitRules.walk(START, day(67), achieved, TOTAL);

        assertThat(walk.cells()).hasSize(66);
        assertThat(walk.cells()).extracting(Cell::status).containsOnly(CellStatus.ACHIEVED);
        assertThat(walk.streak().current()).isEqualTo(66);
        assertThat(walk.streak().ifAchievedToday()).isEqualTo(66);
        assertThat(walk.streak().achievedToday()).isTrue();
        assertThat(walk.achievedDays()).isEqualTo(66);

        Walk muchLater = HabitRules.walk(START, day(120), achievedRange(1, 66), TOTAL);
        assertThat(muchLater.streak().current()).isEqualTo(66);
        assertThat(muchLater.streak().achievedToday()).isFalse();
    }

    @Test
    void currentDayFollowsTheCalendarEvenWhenDaysAreSkipped() {
        assertThat(HabitRules.currentDay(START, START, TOTAL)).isEqualTo(1);
        assertThat(HabitRules.currentDay(START, day(10), TOTAL)).isEqualTo(10);
        assertThat(HabitRules.currentDay(START, START.minusDays(3), TOTAL)).isEqualTo(1);
    }

    @Test
    void heatmapAlwaysHas66CellsInOrder() {
        Walk walk = HabitRules.walk(START, day(5), achievedOn(1, 2), TOTAL);

        assertThat(walk.cells()).hasSize(66);
        assertThat(walk.cells().get(0)).isEqualTo(new Cell(1, START, CellStatus.ACHIEVED));
        assertThat(walk.cells().get(65).day()).isEqualTo(66);
        assertThat(walk.cells().get(65).date()).isEqualTo(START.plusDays(65));
        assertThat(walk.cells().get(65).status()).isEqualTo(CellStatus.FUTURE);
    }

    // ── 레슨 일차·게이트·복습 큐 ──────────────────────────────

    @Test
    void lessonDayIsTheLatestRegisteredDayNotAfterCurrentDay() {
        assertThat(HabitRules.lessonDay(days(1, 2, 3, 4, 5), 3)).isEqualTo(3);
        assertThat(HabitRules.lessonDay(days(1, 2, 3, 4, 5), 9)).isEqualTo(5);
        assertThat(HabitRules.lessonDay(days(1, 2, 4), 3)).isEqualTo(2);
        assertThat(HabitRules.lessonDay(days(3, 4), 2)).isZero();
        assertThat(HabitRules.lessonDay(days(), 5)).isZero();
    }

    @Test
    void day1HasNoGateAndPreviewsItself() {
        assertThat(HabitRules.gateDay(days(1, 2, 3), 1)).isNull();
        assertThat(HabitRules.reviewPlan(days(1, 2, 3), 1)).isEqualTo(new ReviewPlan(List.of(1), true));
    }

    @Test
    void gateIsThePreviousRegisteredDayEvenAcrossSkippedDays() {
        assertThat(HabitRules.gateDay(days(1, 2, 3), 3)).isEqualTo(2);
        assertThat(HabitRules.gateDay(days(1, 2, 4), 4)).isEqualTo(2);
        assertThat(HabitRules.gateDay(days(3, 5), 3)).isNull();
        assertThat(HabitRules.gateDay(days(), 0)).isNull();
    }

    @Test
    void reviewQueueTakesUpToFourPreviousDaysNewestFirst() {
        assertThat(HabitRules.reviewPlan(days(1, 2, 3, 4, 5, 6, 7), 7))
                .isEqualTo(new ReviewPlan(List.of(6, 5, 4, 3), false));
        assertThat(HabitRules.reviewPlan(days(1, 2, 3), 3)).isEqualTo(new ReviewPlan(List.of(2, 1), false));
        assertThat(HabitRules.reviewPlan(days(1, 2, 4, 8), 8)).isEqualTo(new ReviewPlan(List.of(4, 2, 1), false));
        assertThat(HabitRules.reviewPlan(days(), 0)).isEqualTo(new ReviewPlan(List.of(), false));
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

        assertThat(HabitRules.currentDay(START, before, TOTAL)).isEqualTo(1);
        assertThat(HabitRules.currentDay(START, after, TOTAL)).isEqualTo(2);
    }


    // ── 보충과 스트릭 회복 ────────────────────────────────────

    /** Day 1~3 레슨, 문장 id 는 Day*10 + 0..1 (두 문장씩). */
    private static final Map<Integer, List<Long>> LESSONS = Map.of(1, List.of(10L, 11L), 2, List.of(20L, 21L),
            3, List.of(30L, 31L));

    private static List<SpeakRow> spoke(int onDay, int reps, long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new SpeakRow(id, day(onDay), reps)).toList();
    }

    @Test
    void makeupWithinDeadlineRepairsTheMissedDayWithoutAFreeze() {
        List<SpeakRow> speaks = spoke(4, 5, 20L, 21L); // Day 2 를 Day 4 에 보충
        List<Makeup> makeups = HabitRules.makeups(START, day(4), TOTAL, LESSONS, achievedOn(1, 3), Set.of(), speaks);

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
        Makeup m = HabitRules.makeups(START, day(7), TOTAL, LESSONS, achievedOn(1, 3), Set.of(), speaks).stream()
                .filter(x -> x.day() == 2).findFirst().orElseThrow();

        assertThat(m.done()).isTrue();
        assertThat(m.doneOn()).isEqualTo(day(6));
        assertThat(m.repairs()).isFalse();
    }

    @Test
    void restDayNeedsOneRepAndIsLimitedPerPeriod() {
        Makeup m = HabitRules.makeups(START, day(3), TOTAL, LESSONS, achievedOn(1), Set.of(day(2)),
                spoke(3, 1, 20L, 21L)).get(0);
        assertThat(m.rest()).isTrue();
        assertThat(m.reps()).isEqualTo(1);
        assertThat(m.repairs()).isTrue();

        Set<LocalDate> rest = Set.of(day(2), day(5), day(30), day(31), day(70));
        assertThat(HabitRules.restDaysUsed(START, day(10), rest, Set.of(), TOTAL)).isEqualTo(3);
        assertThat(HabitRules.restDaysUsed(START, day(31), rest, Set.of(), TOTAL)).isEqualTo(1);
        // 알린 뒤 그날 달성했으면 쓰지 않은 것으로 친다. 프로그램 밖(Day 70) 기록은 마지막 구간을 잠식하지 않는다.
        assertThat(HabitRules.restDaysUsed(START, day(10), rest, achievedOn(5), TOTAL)).isEqualTo(2);
        assertThat(HabitRules.restDaysUsed(START, day(66), rest, Set.of(), TOTAL)).isZero();
    }

    @Test
    void makeupKeepsTheStreakOnlyWhenThereIsAStreakToKeep() {
        // Day 1 달성, Day 2·3 빼먹음, 오늘 Day 4. 둘 다 보충하면 이어지므로 둘 다 지킨다.
        List<Makeup> both = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(4), TOTAL, LESSONS, achievedOn(1), Set.of(), List.of()),
                START, day(4), achievedOn(1), TOTAL);
        assertThat(both).extracting(Makeup::keepsStreak).containsExactly(true, true);

        // 처음부터 안 했으면(스트릭 0) 보충해도 지킬 것이 없다. Day 를 열기만 한다.
        List<Makeup> none = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(4), TOTAL, LESSONS, Set.of(), Set.of(), List.of()),
                START, day(4), Set.of(), TOTAL);
        assertThat(none).extracting(Makeup::keepsStreak).containsOnly(false);

        // 기한이 지난 앞날(Day 2, 기한 Day 5)이 이미 끊었으면(프리즈 없음 가정 대신 스트릭 0) 뒤의 보충도 지킬 것이 없다.
        List<Makeup> broken = HabitRules.withStreakEffect(
                HabitRules.makeups(START, day(8), TOTAL, Map.of(2, List.of(20L), 3, List.of(30L)), Set.of(), Set.of(),
                        List.of()),
                START, day(8), Set.of(), TOTAL);
        assertThat(broken).extracting(Makeup::keepsStreak).containsOnly(false);
    }

    @Test
    void makeupDeadlineIsInclusive() {
        Makeup onDeadline = HabitRules.makeups(START, day(6), TOTAL, LESSONS, achievedOn(1, 3), Set.of(),
                spoke(5, 5, 20L, 21L)).get(0); // Day 2 의 기한은 Day 5
        assertThat(onDeadline.repairs()).isTrue();
        Makeup late = HabitRules.makeups(START, day(7), TOTAL, LESSONS, achievedOn(1, 3), Set.of(),
                spoke(6, 5, 20L, 21L)).get(0);
        assertThat(late.repairs()).isFalse();
    }

    @Test
    void todayAndAchievedDaysNeedNoMakeup() {
        assertThat(HabitRules.makeups(START, day(2), TOTAL, LESSONS, achievedOn(1), Set.of(), List.of())).isEmpty();
    }

    private static long seoul(int year, int month, int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute).atZone(SEOUL).toInstant().toEpochMilli();
    }
}
