package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.ReservationStatus;

import java.util.List;

// 작업 완료 결과 (B-25, BE-13) - 이미 완료된 예약이면 alreadyCompleted true (이력을 더 만들지 않음)
// version: 바뀐 예약 버전 (정정할 때 보냄), closedChangeIds: 함께 끝낸 진행 중 변경 요청
public record ReservationCompleteResponse(Long reservationId, ReservationStatus status, Integer version,
                                          boolean alreadyCompleted, List<Long> closedChangeIds) {
}
