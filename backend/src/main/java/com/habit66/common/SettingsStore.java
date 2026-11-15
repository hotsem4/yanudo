package com.habit66.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** app_setting 테이블의 유일한 쓰기 소유자. */
@Component
public class SettingsStore {

    private static final String START_DATE = "start_date";

    private final JdbcTemplate jdbc;
    private final WriteLock writes;

    public SettingsStore(JdbcTemplate jdbc, WriteLock writes) {
        this.jdbc = jdbc;
        this.writes = writes;
    }

    public Optional<String> get(String key) {
        List<String> rows = jdbc.queryForList("SELECT value FROM app_setting WHERE key = ?", String.class, key);
        return rows.stream().filter(v -> v != null && !v.isBlank()).findFirst();
    }

    public void put(String key, String value) {
        writes.run(() -> jdbc.update("""
                INSERT INTO app_setting (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """, key, value));
    }

    public Optional<LocalDate> startDate() {
        return get(START_DATE).flatMap(value -> {
            try {
                return Optional.of(LocalDate.parse(value));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        });
    }

    public void setStartDate(LocalDate date) {
        put(START_DATE, date.toString());
    }
}
