package com.habit66.progress;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

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

    @PostMapping("/lessons/import")
    public ImportResult importLessons(@RequestBody LessonImport body) {
        return catalog.importLessons(body);
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
