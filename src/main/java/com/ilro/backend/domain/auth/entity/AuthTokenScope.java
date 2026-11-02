package com.ilro.backend.domain.auth.entity;

// 고객 토큰(CUSTOMER)으로 할 수 있는 일 - 인증 목적(VerificationPurpose)과 같은 이름
public enum AuthTokenScope {
    RESERVATION_LOOKUP, // 내 예약 조회 (C14)
    INQUIRY_ACCESS // 기존 상담 이어가기 (C03), 문의를 고르면 새 문의 토큰 발급
}
