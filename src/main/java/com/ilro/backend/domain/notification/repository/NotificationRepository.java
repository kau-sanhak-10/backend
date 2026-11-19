package com.ilro.backend.domain.notification.repository;

import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 같은 이벤트는 알림 하나 (uq_notification_dedupe_key)
    boolean existsByDedupeKey(String dedupeKey);

    // 한 사건의 알림들 - 같은 예약 확정 요청을 다시 받았을 때 처음 응답의 notifications를 다시 만듦 (명세 1.6·5.10)
    List<Notification> findByRefTypeAndRefIdAndEventTypeOrderByIdAsc(NotificationRefType refType, Long refId,
                                                                     NotificationEventType eventType);

    // 사장님 홈의 미확인 알림 수 (B-10) - 앱 안 알림만 (푸시는 같은 사건을 다른 채널로 보낸 것)
    long countByOwnerIdAndRecipientAndChannelAndReadAtIsNull(Long ownerId, NotificationRecipient recipient,
                                                             NotificationChannel channel);

    // 사장님 업무 알림 목록 (B-26, BE-14) - 앱 안 알림 전체 / 안 읽은 것만
    // 정렬·쪽은 Pageable로 (서비스에서 최신순)
    Page<Notification> findByOwnerIdAndRecipientAndChannel(Long ownerId, NotificationRecipient recipient,
                                                           NotificationChannel channel, Pageable pageable);

    Page<Notification> findByOwnerIdAndRecipientAndChannelAndReadAtIsNull(Long ownerId,
                                                                          NotificationRecipient recipient,
                                                                          NotificationChannel channel,
                                                                          Pageable pageable);

    // 읽음 처리 대상 (B-26) - 다른 업체 알림·푸시/문자 행은 없는 것처럼
    // 행을 잠가 동시에 읽어도 처음 읽은 시각만 남음 (3초 넘게 기다리면 실패)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<Notification> findByIdAndOwnerIdAndRecipientAndChannel(Long id, Long ownerId,
                                                                    NotificationRecipient recipient,
                                                                    NotificationChannel channel);
}