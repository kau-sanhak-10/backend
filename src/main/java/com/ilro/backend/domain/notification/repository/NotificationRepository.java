package com.ilro.backend.domain.notification.repository;

import com.ilro.backend.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 같은 이벤트는 알림 하나 (uq_notification_dedupe_key)
    boolean existsByDedupeKey(String dedupeKey);
}
