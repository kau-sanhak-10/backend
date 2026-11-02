package com.ilro.backend.domain.auth.entity;

// 문자 인증 목적 - 목적별로 권한 분리, 고객 인증으로 사장님 세션 발급 금지
public enum VerificationPurpose {
    OWNER_SIGNUP, // 사장님 가입 신청
    OWNER_LOGIN, // 사장님 로그인
    ADMIN_LOGIN, // 운영자 로그인
    CUSTOMER_VERIFY, // 고객 본인 확인 (예약 확정 직전)
    INQUIRY_ACCESS, // 기존 상담 이어가기 (C03)
    RESERVATION_LOOKUP // 내 예약 조회 (C14)
}
