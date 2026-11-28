package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.dto.OwnerNotificationListResponse;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.exception.NotificationErrorCode;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 사장님 업무 알림 목록·읽음 (B-26, BE-14)
// 목록은 사장님 몫의 앱 안 알림(OWNER·IN_APP)만 - 같은 사건의 푸시 행은 발송용이라 보여 주지 않음
// 푸시 발송 실패·수신 설정과 상관없이 앱 안 알림은 남음 (명세 5.6·5.10)
// 업체는 토큰의 업체로만 - 다른 업체 알림은 없는 것처럼 404
@Service
@RequiredArgsConstructor
public class OwnerNotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    // unread가 true면 안 읽은 것만, page는 0부터
    @Transactional(readOnly = true)
    public OwnerNotificationListResponse getNotifications(AuthPrincipal principal, Boolean unread, Integer page,
                                                          Integer size) {
        Pageable pageable = pageOf(page, size);
        Page<Notification> found = Boolean.TRUE.equals(unread)
                ? notificationRepository.findByOwnerIdAndRecipientAndChannelAndReadAtIsNull(principal.ownerId(),
                NotificationRecipient.OWNER, NotificationChannel.IN_APP, pageable)
                : notificationRepository.findByOwnerIdAndRecipientAndChannel(principal.ownerId(),
                NotificationRecipient.OWNER, NotificationChannel.IN_APP, pageable);
        return OwnerNotificationListResponse.from(found, safetyIds(found.getContent()));
    }

    // 이미 읽었으면 처음 읽은 시각 그대로 200 (두 번 눌러도 같은 결과)
    @Transactional
    public OwnerNotificationListResponse.Item read(AuthPrincipal principal, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndOwnerIdAndRecipientAndChannel(notificationId,
                        principal.ownerId(), NotificationRecipient.OWNER, NotificationChannel.IN_APP)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead(clock.instant());
        return OwnerNotificationListResponse.Item.from(notification,
                safetyIds(List.of(notification)).contains(notificationId));
    }

    private Set<Long> safetyIds(List<Notification> notifications) {
        List<Long> ids = notifications.stream()
                .filter(notification -> notification.getEventType() == NotificationEventType.NEEDS_CHECK)
                .map(Notification::getId).toList();
        return ids.isEmpty() ? Set.of() : notificationRepository.findSafetyNotificationIds(ids);
    }

    // 문의 목록(B-12)과 같은 기준 - 최신순(id 내림차순)
    private static Pageable pageOf(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        List<FieldErrorResponse> errors = new ArrayList<>();
        if (pageNumber < 0 || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            errors.add(new FieldErrorResponse("page", "0 이상의 쪽 번호를 보내 주세요"));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            errors.add(new FieldErrorResponse("size", "1 ~ " + MAX_PAGE_SIZE + " 사이여야 합니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        return PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id"));
    }
}
