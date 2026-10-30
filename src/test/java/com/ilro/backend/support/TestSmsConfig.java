package com.ilro.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 테스트에서만 문자 발송기를 바꿔 끼움 (@Import(TestSmsConfig.class))
@TestConfiguration
public class TestSmsConfig {

    @Bean
    @Primary
    public CapturingSmsSender capturingSmsSender() {
        return new CapturingSmsSender();
    }
}
