package com.habit66.content;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.LogicalClock;
import com.habit66.common.SettingsStore;
import com.habit66.content.CatalogService.ImportResult;

/** 첫 실행: 레슨이 비어 있으면 시드를 넣고, 시작일이 없으면 오늘로 정한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentSeeder.class);
    private static final String SEED = "seed/lessons-day01-05.json";

    private final CatalogService catalog;
    private final SettingsStore settings;
    private final LogicalClock clock;
    private final ObjectMapper mapper;

    public ContentSeeder(CatalogService catalog, SettingsStore settings, LogicalClock clock, ObjectMapper mapper) {
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (catalog.current().isEmpty()) {
            try (InputStream in = new ClassPathResource(SEED).getInputStream()) {
                ImportResult result = catalog.importLessons(mapper.readValue(in, LessonImport.class));
                log.info("Seeded {} lessons, {} sentences", result.lessons(), result.created());
            }
        }
        if (settings.startDate().isEmpty()) {
            settings.setStartDate(clock.today());
            log.info("Start date set to {}", clock.today());
        }
    }
}
