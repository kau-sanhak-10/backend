package com.ilro.backend.domain.inquiry.entity;

// 접수 항목 확인 여부 - 추정값을 사진 확인으로 표시 금지
public enum InquiryFieldStatus {
    CONFIRMED,
    UNCONFIRMED // unconfirmed_reason에 이유 기록
}
