package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 사장님 확인 필요 알림 (앱 안 알림) - AI 판단·고객의 직접 상담·전화 회신 요청이 같은 알림을 씀
// 같은 사건은 하나만 (dedupeKey, uq_notification_dedupe_key) - 푸시 발송은 BE-14
@Component
@RequiredArgsConstructor
public class NeedsCheckNotifier {

    private final NotificationRepository notificationRepository;

    public void notify(Inquiry inquiry, String dedupeKey) {
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }
        notificationRepository.save(Notification.builder()
                .owner(inquiry.getOwner())
                .recipient(NotificationRecipient.OWNER)
                .eventType(NotificationEventType.NEEDS_CHECK)
                .refType(NotificationRefType.INQUIRY)
                .refId(inquiry.getId())
                .channel(NotificationChannel.IN_APP)
                .dedupeKey(dedupeKey)
                .build());
    }
}
