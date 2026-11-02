package com.ilro.backend.domain.inquiry.entity;

// 문의 상태
public enum InquiryStatus {
    COLLECTING, // 정보 수집 중
    SCHEDULING, // 시간 선택 중
    OWNER_CHECK, // 사장님 확인 필요
    BOOKED, // 예약됨
    UNSERVICEABLE, // 서비스 불가 (지역 밖·미제공 서비스, C09)
    CLOSED // 종료 (예약 취소가 아님)
}
