package com.habit66.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.habit66.config.AppProperties;

/**
 * "지금"과 논리 날짜의 유일한 출처. 롤오버 시각(기본 03시) 이전은 전날로 친다.
 */
@Component
public class LogicalClock {

    /** 논리 날짜 하루의 시작(포함)과 끝(제외) epoch ms. */
    public record Bounds(long from, long until) {
    }

    private final Clock clock;
    private final ZoneId zone;
    private final int rolloverHour;

    @Autowired
    public LogicalClock(Clock clock, AppProperties props) {
        this(clock, props.zone(), props.dayRolloverHour());
    }

    public LogicalClock(Clock clock, ZoneId zone, int rolloverHour) {
        if (rolloverHour < 0 || rolloverHour > 23) {
            throw new IllegalArgumentException("day-rollover-hour must be 0..23");
        }
        this.clock = clock;
        this.zone = zone;
        this.rolloverHour = rolloverHour;
    }

    public long now() {
        return clock.millis();
    }

    public LocalDate today() {
        return dateOf(now());
    }

    public LocalDate dateOf(long epochMs) {
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone);
        LocalDate date = local.toLocalDate();
        return local.getHour() < rolloverHour ? date.minusDays(1) : date;
    }

    public Bounds bounds(LocalDate date) {
        LocalTime rollover = LocalTime.of(rolloverHour, 0);
        return new Bounds(wallClock(date, rollover), wallClock(date.plusDays(1), rollover));
    }

    /** 달력 날짜 {@code date}의 벽시계 {@code time}에 해당하는 epoch ms. */
    public long wallClock(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli();
    }

    public LocalTime wallTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toLocalTime();
    }

    public ZoneId zone() {
        return zone;
    }

    public int rolloverHour() {
        return rolloverHour;
    }
}
