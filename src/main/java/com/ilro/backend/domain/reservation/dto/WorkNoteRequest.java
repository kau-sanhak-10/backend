package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 작업 메모 저장 (B-24, BE-13) - 메모 전체를 바꿈, 빈 문자열이면 메모를 비움
public record WorkNoteRequest(
        @NotNull(message = "필수 값입니다") @Size(max = 2000, message = "2000자 이하여야 합니다")
        String content) {
}
