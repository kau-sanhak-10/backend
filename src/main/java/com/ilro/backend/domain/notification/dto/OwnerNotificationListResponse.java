package com.ilro.backend.domain.notification.dto;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.global.common.util.KstTime;
import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.List;

// 사장님 업무 알림 목록 (B-26, BE-14) - 최근 알림부터
// 목록 공통 형식 (명세 1.2) - page는 0부터, isLast가 아니면 page+1로 다음 쪽을 요청
public record OwnerNotificationListResponse(List<Item> content, int page, int size, long totalElements,
                                            int totalPages, boolean isLast) {

    public static OwnerNotificationListResponse from(Page<Notification> found) {
        return new OwnerNotificationListResponse(found.getContent().stream().map(Item::from).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages(),
                found.isLast());
    }

    // 알림 한 건 - 문구는 화면이 eventType으로 만들고, 누르면 refType·refId로 문의·예약 화면으로 이동
    // readAt: 안 읽었으면 null
    public record Item(Long notificationId, NotificationEventType eventType, NotificationRefType refType,
                       Long refId, OffsetDateTime createdAt, OffsetDateTime readAt) {

        public static Item from(Notification notification) {
            return new Item(notification.getId(), notification.getEventType(), notification.getRefType(),
                    notification.getRefId(), KstTime.of(notification.getCreatedAt()),
                    KstTime.of(notification.getReadAt()));
        }
    }
}