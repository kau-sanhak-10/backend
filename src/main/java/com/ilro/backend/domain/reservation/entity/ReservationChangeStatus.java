package com.ilro.backend.domain.reservation.entity;

// 예약 변경 요청 상태 - APPLIED 전까지 기존 예약 유지
public enum ReservationChangeStatus {
    REQUESTED, // 고객 요청 접수
    PROPOSED, // 사장님이 시간 제안
    ACCEPTED, // 고객 수락, 서버 검사 중
    REJECTED, // 고객이 제안 거절
    APPLIED, // 변경 확정
    FAILED, // 최종 검사 실패
    EXPIRED, // 제안 기한 지남 (기존 예약 유지)
    CLOSED // 진행 중에 예약 취소·사장님이 요청 닫음 (closeReason)
}
