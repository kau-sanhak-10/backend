package com.ilro.backend.domain.auth.dto;

import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 인증번호 발송 - phone은 010-1234-5678·01012345678 모두 받음
// linkToken: 상담 복구(INQUIRY_ACCESS)·예약 조회(RESERVATION_LOOKUP)에서 어느 업체인지 (명세 5.1)
//   예약 전 번호 확인(CUSTOMER_VERIFY)은 본문 대신 Authorization 헤더의 문의 토큰으로 문의를 정함
public record SendVerificationRequest(
        @NotNull(message = "필수 값입니다") VerificationPurpose purpose,
        @NotBlank(message = "필수 값입니다") String phone,
        @Size(max = 100, message = "100자 이하여야 합니다") String linkToken) {
}
