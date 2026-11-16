package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationSendStatus;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 고객 예약 확정 결과 (C-13, 명세 5.2) - 같은 요청을 다시 받으면 같은 모양을 200으로
// notifications: 그 시점의 알림 상태 (대개 PENDING), 없어도 빈 배열 - 발송 실패가 예약 성공을 바꾸지 않음 (5.10)
public record ReservationConfirmResponse(Long reservationId, ReservationStatus status, OffsetDateTime startAt,
                                         OffsetDateTime endAt, int version,
                                         List<NotificationResult> notifications) {

    public record NotificationResult(NotificationRecipient recipient, NotificationChannel channel,
                                     NotificationSendStatus sendStatus) {

        static NotificationResult from(Notification notification) {
            return new NotificationResult(notification.getRecipient(), notification.getChannel(),
                    notification.getSendStatus());
        }
    }

    public static ReservationConfirmResponse of(Reservation reservation, List<Notification> notifications) {
        return new ReservationConfirmResponse(reservation.getId(), reservation.getStatus(),
                KstTime.of(reservation.getStartAt()), KstTime.of(reservation.getEndAt()), reservation.getVersion(),
                notifications.stream().map(NotificationResult::from).toList());
    }
}
