package com.ilro.backend.domain.reservation.dto;

// 제안 수락 결과 (명세 5.3) - 변경 APPLIED와 바뀐 예약 (version +1)
public record ChangeAcceptResponse(ChangeSnapshot change, ReservationSnapshot reservation) {
}
