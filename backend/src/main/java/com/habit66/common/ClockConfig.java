package com.habit66.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** 테스트는 {@code @Primary} Clock 빈으로 시간을 고정한다. */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
