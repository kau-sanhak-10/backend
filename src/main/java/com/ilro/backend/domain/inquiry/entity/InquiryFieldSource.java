package com.ilro.backend.domain.inquiry.entity;

// 접수 항목 값의 출처
public enum InquiryFieldSource {
    CUSTOMER, // 고객 설명
    PHOTO, // 사진 확인 (photo_id 필수)
    OWNER // 사장님 수정 (AI가 덮어쓰지 않음)
}
