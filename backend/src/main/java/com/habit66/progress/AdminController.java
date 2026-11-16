package com.habit66.progress;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.ApiException;
import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.content.CatalogService;
import com.habit66.content.CatalogService.ImportResult;
import com.habit66.content.LessonImport;

/** 콘텐츠 임포트와 설정. 저장은 각 테이블의 소유자(CatalogService, SettingsStore)가 한다. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public record SettingsView(String startDate, String timezone, int dayRolloverHour) {
    }

    public record StartDateRequest(String startDate) {
    }

    private final CatalogService catalog;
    private final SettingsStore settings;
    private final LogicalClock clock;

    public AdminController(CatalogService catalog, SettingsStore settings, LogicalClock clock) {
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
    }

    /** @param warnings 이미 지난 날짜의 Day 를 새로 넣는 등 확인이 필요한 점(임포트는 그대로 한다) */
    public record ImportResponse(int lessons, int created, int updated, List<String> warnings) {
    }

    @PostMapping("/lessons/import")
    public ImportResponse importLessons(@RequestBody LessonImport body) {
        List<String> warnings = pastDayWarnings(body);
        ImportResult r = catalog.importLessons(body);
        return new ImportResponse(r.lessons(), r.created(), r.updated(), warnings);
    }

    /**
     * 그 Day 의 날짜가 이미 지났는데 레슨이 없던 Day 를 새로 넣으면, 그날을 달성하지 않았을 때 그날 일정이 이 레슨으로 바뀐다
     * (복습 순환으로 다른 레슨을 했던 날이라도). 본 적 없는 레슨이 보충 대상이 되고 말하기 탭에 나타날 수 있어 알린다.
     */
    private List<String> pastDayWarnings(LessonImport body) {
        if (body == null || body.lessons() == null) {
            return List.of();
        }
        LocalDate today = clock.today();
        LocalDate start = settings.startDate().orElse(today);
        int currentDay = HabitRules.currentDay(start, today);
        java.util.Set<Integer> existing = catalog.current().days();
        List<String> out = new java.util.ArrayList<>();
        for (LessonImport.LessonIn lesson : body.lessons()) {
            if (lesson == null || lesson.day() == null || existing.contains(lesson.day()) || lesson.day() > currentDay) {
                continue;
            }
            if (lesson.day() == currentDay) {
                out.add("Day " + lesson.day() + " 은 오늘이에요. 오늘 아직 달성하지 않았다면 오늘 공부할 레슨이 이 레슨으로 바뀌고, "
                        + "지금까지 본 복습 순환 문장은 복습으로 넘어가요.");
                continue;
            }
            out.add("Day " + lesson.day() + " 은 이미 지난 날짜(" + start.plusDays(lesson.day() - 1L)
                    + ")의 Day 예요. 그날을 달성하지 않았다면 그날 일정이 이 레슨으로 바뀌어 보충 대상이 될 수 있어요.");
        }
        return List.copyOf(out);
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        LocalDate start = settings.startDate().orElseGet(clock::today);
        return new SettingsView(start.toString(), clock.zone().getId(), clock.rolloverHour());
    }

    /** 시작일을 바꾸면 Day 귀속·히트맵·스트릭이 새 시작일 기준으로 다시 계산된다(기록은 날짜로 남아 있다). */
    @PutMapping("/settings/start-date")
    public SettingsView setStartDate(@RequestBody StartDateRequest body) {
        LocalDate date;
        try {
            date = LocalDate.parse(body == null || body.startDate() == null ? "" : body.startDate().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_START_DATE", "start_date 는 YYYY-MM-DD 형식이어야 합니다.");
        }
        if (date.isAfter(clock.today())) {
            throw ApiException.badRequest("INVALID_START_DATE", "시작일은 오늘보다 뒤일 수 없습니다.");
        }
        settings.setStartDate(date);
        return settings();
    }
}
