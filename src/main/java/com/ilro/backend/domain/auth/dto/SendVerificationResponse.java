package com.ilro.backend.domain.auth.dto;

import java.time.OffsetDateTime;

// 인증번호 발송 결과 - 인증번호 원문은 담지 않음
public record SendVerificationResponse(Long verificationId, OffsetDateTime expiresAt,
                                       OffsetDateTime resendAvailableAt) {
}
