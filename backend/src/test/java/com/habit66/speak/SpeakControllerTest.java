package com.habit66.speak;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.habit66.progress.DaySnapshot.Makeup;

class SpeakControllerTest {

    private static Makeup makeup(int dayOfMonth, boolean keepsStreak) {
        LocalDate date = LocalDate.of(2026, 11, dayOfMonth);
        return new Makeup(3, date, false, 5, date.plusDays(3), List.of(30L), List.of(), null, keepsStreak);
    }

    @Test
    void prefersTheMakeupThatKeepsTheStreakLikeTheDashboard() {
        List<Makeup> makeups = List.of(makeup(20, false), makeup(22, true));
        assertThat(SpeakController.pickMakeup(makeups, 3, false).date()).isEqualTo(LocalDate.of(2026, 11, 22));
    }

    @Test
    void anExpiredMakeupIsIgnoredOnceTheLessonWasAchievedSomeOtherDay() {
        List<Makeup> makeups = List.of(makeup(20, false));
        assertThat(SpeakController.pickMakeup(makeups, 3, true)).isNull();
        assertThat(SpeakController.pickMakeup(makeups, 3, false)).isNotNull();
        // 스트릭을 지키는 보충은 달성한 적이 있어도 남긴다.
        assertThat(SpeakController.pickMakeup(List.of(makeup(22, true)), 3, true)).isNotNull();
    }
}
