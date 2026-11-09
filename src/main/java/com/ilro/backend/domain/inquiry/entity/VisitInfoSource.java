package com.ilro.backend.domain.inquiry.entity;

// 방문정보를 마지막으로 정한 쪽 (ck_inquiry_visit_info_source) - OWNER면 고객이 다른 값으로 바꾸지 못함
public enum VisitInfoSource {
    CUSTOMER, // 고객 방문정보 저장 (C-06)
    OWNER // 사장님 수정 (B-14)
}
