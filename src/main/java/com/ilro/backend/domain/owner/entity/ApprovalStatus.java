package com.ilro.backend.domain.owner.entity;

// 업체 가입 승인 상태 - 운영 상태(OwnerStatus)와 별개
public enum ApprovalStatus {
    PENDING, // 대기
    APPROVED, // 승인
    REJECTED // 거절 (rejection_reason 필수)
}
