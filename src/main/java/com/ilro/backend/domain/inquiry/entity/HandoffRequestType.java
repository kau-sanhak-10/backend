package com.ilro.backend.domain.inquiry.entity;

// 사람 응대 요청 종류
public enum HandoffRequestType {
    DIRECT_CHAT, // 직접 상담 (C10)
    CALLBACK // 전화 회신 (C18, phone 필수)
}
