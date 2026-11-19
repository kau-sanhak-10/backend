package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 예약 지금 상태 - 버전 충돌·변경 결과·기존 예약 유지 응답에 같이 담음 (명세 3.5·5.3)
public record ReservationSnapshot(Long reservationId, ReservationStatus status, OffsetDateTime startAt,
                                  OffsetDateTime endAt, String address, int version) {

    public static ReservationSnapshot from(Reservation reservation) {
        return new ReservationSnapshot(reservation.getId(), reservation.getStatus(),
                KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()), reservation.getAddress(),
                reservation.getVersion());
    }
}
