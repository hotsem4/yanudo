package com.habit66.progress;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.config.AppProperties;
import com.habit66.content.Catalog;
import com.habit66.content.Catalog.LessonView;
import com.habit66.content.Catalog.SentenceView;
import com.habit66.content.CatalogService;
import com.habit66.progress.DaySnapshot.Focus;
import com.habit66.progress.DaySnapshot.Gate;
import com.habit66.progress.DaySnapshot.Makeup;
import com.habit66.progress.DaySnapshot.RestDay;
import com.habit66.progress.DaySnapshot.MorningLock;
import com.habit66.progress.DaySnapshot.Review;
import com.habit66.progress.HabitRules.ReviewPlan;
import com.habit66.progress.HabitRules.Schedule;
import com.habit66.progress.HabitRules.Walk;
import com.habit66.progress.ProgressFacts.FocusRow;
import com.habit66.progress.ProgressFacts.ViewRow;

/** 저장된 사실을 읽어 {@link HabitRules}로 하루 상태를 계산한다. 파생값은 어디에도 저장하지 않는다. */
@Service
public class DayStateService {

    private static final int MORNING_SENTENCES = 3;

    private final LogicalClock clock;
    private final SettingsStore settings;
    private final CatalogService catalog;
    private final ProgressFacts facts;
    private final AppProperties props;
    private final TransactionTemplate readTx;

    public DayStateService(LogicalClock clock, SettingsStore settings, CatalogService catalog, ProgressFacts facts,
            AppProperties props, PlatformTransactionManager txManager) {
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
        this.settings = settings;
        this.catalog = catalog;
        this.facts = facts;
        this.props = props;
    }

    /** 사실 조회를 한 읽기 트랜잭션으로 묶어, 진행 수와 달성 여부가 서로 다른 시점의 값이 되지 않게 한다. */
    public DaySnapshot snapshot() {
        return readTx.execute(status -> read());
    }

    private DaySnapshot read() {
        long now = clock.now();
        LocalDate today = clock.dateOf(now);
        LogicalClock.Bounds bounds = clock.bounds(today);
        Catalog cat = catalog.current();
        LocalDate start = settings.startDate().orElse(today);
        int totalDays = props.totalDays();

        int currentDay = HabitRules.currentDay(start, today);
        Schedule schedule = HabitRules.schedule(start, today, cat.days(), facts.achievements());
        int lessonDay = schedule.lessonOn(today);
        LessonView lesson = cat.lesson(lessonDay).orElse(null);
        Set<LocalDate> achieved = facts.achievedDates();
        Set<LocalDate> restDays = facts.restDays();
        Map<Integer, List<Long>> lessons = new TreeMap<>();
        for (int day : cat.days()) {
            cat.lesson(day).ifPresent(l -> lessons.put(day, l.sentenceIds()));
        }
        List<Makeup> makeups = HabitRules.withStreakEffect(
                HabitRules.makeups(start, today, schedule, lessons, achieved, restDays, facts.speaks()),
                start, today, achieved, totalDays);
        Walk walk = HabitRules.walk(start, today, achieved, HabitRules.repairedDates(makeups), totalDays);
        RestDay restDay = new RestDay(restDays.contains(today),
                Math.max(0, HabitRules.REST_DAYS_PER_PERIOD
                        - HabitRules.restDaysUsed(start, today, restDays, achieved)),
                HabitRules.REST_DAYS_PER_PERIOD,
                HabitRules.period(start, today) * HabitRules.PERIOD_DAYS + 1,
                (HabitRules.period(start, today) + 1) * HabitRules.PERIOD_DAYS);

        Set<Long> todayIds = lesson == null ? Set.of() : new HashSet<>(lesson.sentenceIds());
        Set<Long> audioSeen = new TreeSet<>();
        Set<Long> cardSeen = new TreeSet<>();
        Set<Long> reviewSeen = new TreeSet<>();
        for (ViewRow row : facts.views(today)) {
            if ("REVIEW".equals(row.session())) {
                reviewSeen.add(row.sentenceId());
            } else if (todayIds.contains(row.sentenceId())) {
                ("AUDIO".equals(row.via()) ? audioSeen : cardSeen).add(row.sentenceId());
            } else {
                // 저장할 때는 오늘 레슨이었지만 시작일 변경·임포트로 지금은 아닌 행. 쓰기 때의 재분류와 같게 복습으로 센다.
                reviewSeen.add(row.sentenceId());
            }
        }

        Integer gateDay = HabitRules.gateDay(schedule, start, today);
        List<Long> unlockIds = gateDay == null ? List.of()
                : cat.lesson(gateDay).map(LessonView::sentenceIds).orElse(List.of());
        boolean gateRequired = gateDay != null;
        Gate gate = new Gate(gateRequired, !gateRequired || reviewSeen.containsAll(unlockIds), gateDay, unlockIds,
                List.copyOf(reviewSeen));

        ReviewPlan plan = HabitRules.reviewPlan(schedule, start, today);
        List<SentenceView> reviewSentences = new ArrayList<>();
        for (int day : plan.days()) {
            cat.lesson(day).ifPresent(l -> reviewSentences.addAll(l.sentences()));
        }
        int reviewSeenCount = (int) reviewSentences.stream().filter(s -> reviewSeen.contains(s.id())).count();
        Review review = new Review(plan.days(), plan.preview(), List.copyOf(reviewSentences), reviewSeenCount);

        MorningLock morningLock = new MorningLock(
                clock.wallClock(today, LocalTime.parse(props.morningLock().start())),
                clock.wallClock(today, LocalTime.parse(props.morningLock().end())),
                facts.morningLock(today).orElse("NONE"),
                morningSentences(lesson));

        List<FocusRow> focusRows = facts.focus(today);
        Focus focus = new Focus(focusRows.stream().mapToLong(FocusRow::seconds).sum(),
                focusRows.stream().map(FocusRow::startedAt).toList());

        return new DaySnapshot(today, now, bounds.from(), bounds.until(), start, currentDay, totalDays, lessonDay,
                cat.maxDay(), lessonDay == 0 || schedule.repeat(today), HabitRules.round(currentDay, totalDays),
                HabitRules.roundDay(currentDay, totalDays), walk.streak(), walk.achievedDays(), walk.cells(),
                walk.pastRounds(), lesson, List.copyOf(audioSeen),
                List.copyOf(cardSeen), gate, review, morningLock, focus, facts.marks(), makeups, restDay);
    }

    /** core 문장 3개. 모자라면 나머지를 seq 순으로 채운다. */
    private static List<SentenceView> morningSentences(LessonView lesson) {
        if (lesson == null) {
            return List.of();
        }
        List<SentenceView> picked = new ArrayList<>(
                lesson.sentences().stream().filter(SentenceView::core).limit(MORNING_SENTENCES).toList());
        for (SentenceView sentence : lesson.sentences()) {
            if (picked.size() >= MORNING_SENTENCES) {
                break;
            }
            if (!sentence.core()) {
                picked.add(sentence);
            }
        }
        picked.sort(Comparator.comparingInt(SentenceView::seq));
        return List.copyOf(picked);
    }
}
