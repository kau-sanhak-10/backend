package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import jakarta.validation.constraints.NotNull;

// 직접 상담·전화 회신 요청 처리 상태 (B-15·C-18) - IN_PROGRESS(처리 중)·DONE(완료)
public record UpdateHandoffRequest(
        @NotNull(message = "필수 값입니다")
        HandoffStatus status) {
}
