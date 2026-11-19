package com.ilro.backend.domain.inquiry.dto;

import java.time.OffsetDateTime;

// 상담 복구로 받은 새 문의 토큰 (C-03, BE-18) - 상담 시작 응답과 같은 모양, 이 응답으로 한 번만 받음
// 기기마다 따로 발급 - 원래 기기의 문의 토큰은 그대로 쓸 수 있음 (명세 1.5)
public record InquiryAccessTokenResponse(Long inquiryId, String inquiryToken, OffsetDateTime expiresAt) {
}
