package com.ilro.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 테스트에서만 AI 서버를 바꿔 끼움 (@Import(TestAiConfig.class))
@TestConfiguration
public class TestAiConfig {

    @Bean
    @Primary
    public ScriptedAiClient scriptedAiClient() {
        return new ScriptedAiClient();
    }
}
