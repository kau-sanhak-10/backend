package com.ilro.backend.domain.ai;

import com.ilro.backend.domain.ai.service.AiAnalysisQueue;
import com.ilro.backend.domain.ai.service.AiAnalysisService;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Clock;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 분석 작업 표시(문의마다 한 번에 하나) 정리 - 작업이 Error로 끝나도 다음 요청이 다시 돌아야 함
class AiAnalysisQueueRunLoopTest {

    @Test
    @DisplayName("분석 중 Error가 나도 표시를 지워 같은 문의를 다시 분석할 수 있음")
    void errorDoesNotBlockInquiry() {
        AiAnalysisService service = mock(AiAnalysisService.class);
        doThrow(new StackOverflowError("test")).doNothing().when(service).analyze(eq(7L), any(LongSupplier.class));
        AiAnalysisQueue queue = new AiAnalysisQueue(service, mock(InquiryRepository.class), new SyncTaskExecutor(),
                Clock.systemUTC(), true);

        assertThatThrownBy(() -> queue.request(7L)).isInstanceOf(StackOverflowError.class);
        queue.request(7L);

        verify(service, times(2)).analyze(eq(7L), any(LongSupplier.class));
    }

    @Test
    @DisplayName("일반 예외는 기록만 하고 다음 요청을 받음")
    void runtimeExceptionIsLogged() {
        AiAnalysisService service = mock(AiAnalysisService.class);
        doThrow(new IllegalStateException("test")).when(service).analyze(eq(8L), any(LongSupplier.class));
        AiAnalysisQueue queue = new AiAnalysisQueue(service, mock(InquiryRepository.class), new SyncTaskExecutor(),
                Clock.systemUTC(), true);

        queue.request(8L);
        doNothing().when(service).analyze(eq(8L), any(LongSupplier.class));
        queue.request(8L);

        verify(service, times(2)).analyze(eq(8L), any(LongSupplier.class));
    }
}
