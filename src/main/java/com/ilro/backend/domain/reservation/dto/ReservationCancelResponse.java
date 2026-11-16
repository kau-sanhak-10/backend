package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.ReservationStatus;

import java.util.List;

// 예약 취소 결과 (명세 5.4) - 이미 취소된 예약이면 alreadyCanceled true (이력·알림을 더 만들지 않음)
// closedChangeIds: 함께 끝낸 진행 중 변경 요청
public record ReservationCancelResponse(Long reservationId, ReservationStatus status, boolean alreadyCanceled,
                                        List<Long> closedChangeIds) {
}
