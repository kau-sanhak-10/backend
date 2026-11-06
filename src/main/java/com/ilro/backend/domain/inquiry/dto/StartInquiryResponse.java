package com.ilro.backend.domain.inquiry.dto;

import java.time.OffsetDateTime;

// 문의 토큰은 이 응답으로 한 번만 받음 - 브라우저 저장소에 두고 Authorization 헤더로 보냄 (명세 1.5)
public record StartInquiryResponse(Long inquiryId, String inquiryToken, OffsetDateTime expiresAt) {
}
