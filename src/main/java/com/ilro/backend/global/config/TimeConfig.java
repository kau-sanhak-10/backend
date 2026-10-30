package com.ilro.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    // 서비스는 Instant.now() 대신 이 시계를 씀 (테스트에서 시각을 다루기 쉽게)
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
