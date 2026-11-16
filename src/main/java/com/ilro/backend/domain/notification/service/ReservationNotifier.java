package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 예약 사건 알림 기록 (명세 5.10) - 예약 저장과 같은 트랜잭션에서 PENDING 행만 만들고, 보내는 일은 저장이 끝난 뒤 따로 (BE-14)
// 그래서 예약 API의 성공은 발송 결과와 상관없음
// 중복 키: RESERVATION:{예약ID}:{사건}:{수신자}:{채널} - 같은 사건은 하나만 (uq_notification_dedupe_key)
// 고객 문자는 보낼 때 예약에 저장된 고객 번호로 (여기에는 번호를 남기지 않음)
@Component
@RequiredArgsConstructor
public class ReservationNotifier {

    private final NotificationRepository notificationRepository;

    // 고객 예약 확정 (C-13, BE-11) - 고객 문자 + 사장님 푸시
    public List<Notification> confirmed(Reservation reservation) {
        List<Notification> created = new ArrayList<>();
        add(created, reservation, NotificationRecipient.CUSTOMER, NotificationChannel.SMS);
        add(created, reservation, NotificationRecipient.OWNER, NotificationChannel.WEB_PUSH);
        return created;
    }

    private void add(List<Notification> created, Reservation reservation, NotificationRecipient recipient,
                     NotificationChannel channel) {
        String dedupeKey = "RESERVATION:" + reservation.getId() + ":CONFIRMED:" + recipient + ":" + channel;
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }
        created.add(notificationRepository.save(Notification.builder()
                .owner(reservation.getOwner())
                .recipient(recipient)
                .eventType(NotificationEventType.RESERVATION_CONFIRMED)
                .refType(NotificationRefType.RESERVATION)
                .refId(reservation.getId())
                .channel(channel)
                .dedupeKey(dedupeKey)
                .build()));
    }
}
