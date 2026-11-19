package com.ilro.backend.domain.notification.repository;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 같은 이벤트는 알림 하나 (uq_notification_dedupe_key)
    boolean existsByDedupeKey(String dedupeKey);

    // 한 사건의 알림들 - 같은 예약 확정 요청을 다시 받았을 때 처음 응답의 notifications를 다시 만듦 (명세 1.6·5.10)
    List<Notification> findByRefTypeAndRefIdAndEventTypeOrderByIdAsc(NotificationRefType refType, Long refId,
                                                                     NotificationEventType eventType);

    // 사장님 홈의 미확인 알림 수 (B-10) - 앱 안 알림만 (푸시는 같은 사건을 다른 채널로 보낸 것)
    long countByOwnerIdAndRecipientAndChannelAndReadAtIsNull(Long ownerId, NotificationRecipient recipient,
                                                              NotificationChannel channel);
}
