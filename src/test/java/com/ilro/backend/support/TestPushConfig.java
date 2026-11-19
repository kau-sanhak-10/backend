package com.ilro.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 테스트에서만 푸시 발송기를 바꿔 끼움 (@Import(TestPushConfig.class))
@TestConfiguration
public class TestPushConfig {

    @Bean
    @Primary
    public CapturingWebPushSender capturingWebPushSender() {
        return new CapturingWebPushSender();
    }
}