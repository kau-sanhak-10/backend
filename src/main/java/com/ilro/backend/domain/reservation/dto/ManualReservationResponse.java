package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.reservation.dto.ReservationConfirmResponse.NotificationResult;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 수동 예약 결과 (B-18) - 같은 요청을 다시 받으면 같은 모양을 200으로
// outsideBusinessHours: 영업시간 밖 경고를 확인하고 등록한 예약
// notifications: 고객 확정 문자 (대개 PENDING)
public record ManualReservationResponse(Long reservationId, ReservationStatus status, OffsetDateTime startAt,
                                        OffsetDateTime endAt, int version, boolean outsideBusinessHours,
                                        List<NotificationResult> notifications) {

    public static ManualReservationResponse of(Reservation reservation, boolean outsideBusinessHours,
                                               List<Notification> notifications) {
        return new ManualReservationResponse(reservation.getId(), reservation.getStatus(),
                KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()), reservation.getVersion(),
                outsideBusinessHours, notifications.stream().map(NotificationResult::from).toList());
    }
}
