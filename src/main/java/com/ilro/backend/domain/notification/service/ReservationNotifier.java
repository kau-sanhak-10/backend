package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.reservation.entity.ChangedBy;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 예약 사건 알림 기록 (명세 5.10) - 예약 저장과 같은 트랜잭션에서 PENDING 행만 만들고, 보내는 일은 저장이 끝난 뒤 따로 (BE-14)
// 그래서 예약 API의 성공은 발송 결과와 상관없음
// 중복 키: RESERVATION:{예약ID}:{사건}[:{변경ID}]:{수신자}:{채널} - 같은 사건은 하나만 (uq_notification_dedupe_key)
//   변경 사건은 예약 하나에 여러 번 생기므로 변경 ID를 넣음
// 고객 문자는 보낼 때 예약에 저장된 고객 번호로 (여기에는 번호를 남기지 않음) - 고객이 없는 예약이면 고객 알림을 만들지 않음
@Component
@RequiredArgsConstructor
public class ReservationNotifier {

    private static final Target CUSTOMER_SMS = new Target(NotificationRecipient.CUSTOMER, NotificationChannel.SMS);
    private static final Target OWNER_PUSH = new Target(NotificationRecipient.OWNER, NotificationChannel.WEB_PUSH);

    private final NotificationRepository notificationRepository;

    private record Target(NotificationRecipient recipient, NotificationChannel channel) {
    }

    // 고객 예약 확정 (C-13, BE-11) - 고객 문자 + 사장님 푸시
    public List<Notification> confirmed(Reservation reservation) {
        return record(reservation, NotificationEventType.RESERVATION_CONFIRMED, "CONFIRMED", CUSTOMER_SMS, OWNER_PUSH);
    }

    // 사장님 수동 예약 (B-18, BE-12) - 고객 문자만 (사장님이 직접 등록)
    public List<Notification> manuallyBooked(Reservation reservation) {
        return record(reservation, NotificationEventType.RESERVATION_CONFIRMED, "CONFIRMED", CUSTOMER_SMS);
    }

    // 고객 변경 요청 (C-15) - 사장님 푸시
    public List<Notification> changeRequested(ReservationChange change) {
        return record(change.getReservation(), NotificationEventType.CHANGE, "CHANGE:" + change.getId(), OWNER_PUSH);
    }

    // 사장님 시간 제안 (B-19·B-21) - 고객 문자 (눌러도 자동으로 수락하지 않음)
    public List<Notification> changeProposed(ReservationChange change) {
        return record(change.getReservation(), NotificationEventType.CHANGE_PROPOSED,
                "CHANGE_PROPOSED:" + change.getId(), CUSTOMER_SMS);
    }

    // 변경 확정 (C-17) - 고객 문자 + 사장님 푸시
    public List<Notification> changeApplied(ReservationChange change) {
        return record(change.getReservation(), NotificationEventType.CHANGE_APPLIED,
                "CHANGE_APPLIED:" + change.getId(), CUSTOMER_SMS, OWNER_PUSH);
    }

    // 즉시 취소 (C-16·B-20) - 취소한 쪽이 아닌 쪽에 (사장님이 취소하면 고객 문자, 고객이 취소하면 사장님 푸시)
    public List<Notification> canceled(Reservation reservation, ChangedBy by) {
        return record(reservation, NotificationEventType.RESERVATION_CANCELED, "CANCELED",
                by == ChangedBy.CUSTOMER ? OWNER_PUSH : CUSTOMER_SMS);
    }

    private List<Notification> record(Reservation reservation, NotificationEventType eventType, String event,
                                      Target... targets) {
        List<Notification> created = new ArrayList<>();
        for (Target target : targets) {
            if (target.recipient() == NotificationRecipient.CUSTOMER && reservation.getCustomer() == null) {
                continue;
            }
            String dedupeKey = "RESERVATION:" + reservation.getId() + ":" + event + ":" + target.recipient() + ":"
                    + target.channel();
            if (notificationRepository.existsByDedupeKey(dedupeKey)) {
                continue;
            }
            created.add(notificationRepository.save(Notification.builder()
                    .owner(reservation.getOwner())
                    .recipient(target.recipient())
                    .eventType(eventType)
                    .refType(NotificationRefType.RESERVATION)
                    .refId(reservation.getId())
                    .channel(target.channel())
                    .dedupeKey(dedupeKey)
                    .build()));
        }
        return created;
    }
}
