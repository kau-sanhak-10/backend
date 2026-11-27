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

// 사장님 확인 필요 알림 - AI 판단·고객의 직접 상담·전화 회신 요청·예약 문제가 같은 알림을 씀
// 앱 안 알림(B-26 목록)과 푸시를 같이 - 푸시는 발송기가 보냄 (수신 설정을 껐거나 문의가 지워졌으면 건너뜀, DeliveryPlanner)
// 같은 사건은 하나만 (uq_notification_dedupe_key)
//   앱 안 알림: 부르는 쪽이 준 키 그대로 (예전 행과 같은 키라 이미 만든 알림을 다시 만들지 않음)
//   푸시: 그 키 + ":WEB_PUSH"
@Component
@RequiredArgsConstructor
public class NeedsCheckNotifier {

    private static final String PUSH_SUFFIX = ":" + NotificationChannel.WEB_PUSH;

    private final NotificationRepository notificationRepository;

    public void notify(Inquiry inquiry, String dedupeKey) {
        save(inquiry, NotificationChannel.IN_APP, dedupeKey);
        save(inquiry, NotificationChannel.WEB_PUSH, dedupeKey + PUSH_SUFFIX);
    }

    // 채널마다 따로 확인 - 앱 안 알림만 있던 예전 사건에 같은 키가 다시 와도 푸시는 한 번만 생김
    private void save(Inquiry inquiry, NotificationChannel channel, String dedupeKey) {
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            return;
        }
        notificationRepository.save(Notification.builder()
                .owner(inquiry.getOwner())
                .recipient(NotificationRecipient.OWNER)
                .eventType(NotificationEventType.NEEDS_CHECK)
                .refType(NotificationRefType.INQUIRY)
                .refId(inquiry.getId())
                .channel(channel)
                .dedupeKey(dedupeKey)
                .build());
    }
}
