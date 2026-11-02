package com.ilro.backend.domain.owner.entity;

// 업체 운영 상태 - approval_status가 APPROVED일 때만 의미 있음
public enum OwnerStatus {
    READY, // 설정 중
    ACTIVE, // 이용 중
    RESTRICTED, // 이용 제한
    WITHDRAWN // 탈퇴 (고객 예약은 삭제하지 않음)
}
