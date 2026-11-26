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
import com.ilro.backend.domain.notification.entity.NotificationSendStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 같은 이벤트는 알림 하나 (uq_notification_dedupe_key)
    boolean existsByDedupeKey(String dedupeKey);

    // 한 사건의 알림들 - 같은 예약 확정 요청을 다시 받았을 때 처음 응답의 notifications를 다시 만듦 (명세 1.6·5.10)
    // 앱 안 알림(IN_APP)은 뺌 - 처음 응답에도 보낼 알림(문자·푸시)만 들어감 (ReservationNotifier)
    @Query("select n from Notification n where n.refType = :refType and n.refId = :refId "
            + "and n.eventType = :eventType "
            + "and n.channel <> com.ilro.backend.domain.notification.entity.NotificationChannel.IN_APP "
            + "order by n.id asc")
    List<Notification> findByRefTypeAndRefIdAndEventTypeOrderByIdAsc(@Param("refType") NotificationRefType refType,
                                                                     @Param("refId") Long refId,
                                                                     @Param("eventType") NotificationEventType eventType);

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

    // 조회 권한을 확인한 알림 ID만. 같은 문의의 뒤늦은 안전 판단이 이전 일반 알림을 바꾸지 않도록
    // AiAnalysisStore가 기록하는 알림 키의 판단 버전까지 일치시킨다.
    @Query(value = """
            SELECT n.id
            FROM notification n
            WHERE n.id IN (:ids) AND n.event_type = 'NEEDS_CHECK' AND n.ref_type = 'INQUIRY'
              AND EXISTS (
                  SELECT 1 FROM ai_decision d
                  WHERE d.inquiry_id = n.ref_id AND d.stage = 'DECIDE' AND d.status = 'SUCCESS'
                    AND d.input_version IS NOT NULL
                    AND n.dedupe_key = CONCAT('NEEDS_CHECK:INQUIRY:', d.inquiry_id, ':', d.input_version)
                    AND (d.output_raw #> '{result,safety,flag}') = CAST('true' AS jsonb)
              )
            """, nativeQuery = true)
    Set<Long> findSafetyNotificationIds(@Param("ids") Collection<Long> ids);

    // 읽음 처리 대상 (B-26) - 다른 업체 알림·푸시/문자 행은 없는 것처럼
    // 행을 잠가 동시에 읽어도 처음 읽은 시각만 남음 (3초 넘게 기다리면 실패)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<Notification> findByIdAndOwnerIdAndRecipientAndChannel(Long id, Long ownerId,
                                                                    NotificationRecipient recipient,
                                                                    NotificationChannel channel);

    // 발송 대상 (명세 5.10, BE-14 발송기) - 아직 안 보냈거나 실패하고 한도 전인 것, 오래된 것부터 ID만
    // 첫 시도는 바로, 재시도는 실패할 때 정한 다음 시도 시각(nextAttemptAt)이 지난 것만 (Notification.markFailed)
    @Query("select n.id from Notification n where n.channel in :channels and n.sendStatus in :statuses "
            + "and n.attemptCount < :maxAttempts "
            + "and (n.attemptCount = 0 or n.nextAttemptAt <= :now) "
            + "order by n.id")
    List<Long> findDeliverableIds(@Param("channels") Collection<NotificationChannel> channels,
                                  @Param("statuses") Collection<NotificationSendStatus> statuses,
                                  @Param("maxAttempts") int maxAttempts,
                                  @Param("now") Instant now,
                                  Pageable pageable);
}
