package com.ilro.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class AiAsyncConfig {

    // AI 분석 호출용 스레드 - 고객 요청 스레드가 AI 답을 기다리지 않게 (명세 6.1)
    // 대기열이 가득 차면 거절하고, 답이 없는 메시지는 AiAnalysisQueue.sweep이 다시 잡음
    @Bean
    public ThreadPoolTaskExecutor aiAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
