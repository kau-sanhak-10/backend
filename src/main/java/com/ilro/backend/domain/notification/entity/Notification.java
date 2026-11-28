package com.ilro.backend.domain.notification.entity;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// 알림 (받는 쪽·채널별 1행)
@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationRecipient recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationEventType eventType;

    // refType이 가리키는 문의 또는 예약 ID (FK 없음)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationRefType refType;

    @Column(nullable = false)
    private Long refId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    // 같은 이벤트 중복 알림 방지 (B26·C17)
    @Column(nullable = false, length = 100)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationSendStatus sendStatus = NotificationSendStatus.PENDING;

    @Column(nullable = false)
    private int attemptCount = 0;

    // 실패한 뒤 다시 보내도 되는 시각 (실패할 때 정함) - 첫 시도 전·보낸 뒤·한도를 넘긴 뒤에는 null
    private Instant nextAttemptAt;

    // 외부 발송 실패와 무관하게 미확인 업무 유지
    private Instant readAt;

    @Builder
    private Notification(Owner owner, NotificationRecipient recipient, NotificationEventType eventType,
                         NotificationRefType refType, Long refId, NotificationChannel channel, String dedupeKey) {
        this.owner = owner;
        this.recipient = recipient;
        this.eventType = eventType;
        this.refType = refType;
        this.refId = refId;
        this.channel = channel;
        this.dedupeKey = dedupeKey;
    }

    // 사장님이 알림을 읽음 (B-26) - 이미 읽었으면 처음 읽은 시각 그대로
    // 발송 상태(sendStatus)와는 따로 - 푸시가 실패해도 읽음은 웹에서 처리
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    // 재시도 한도 (명세 5.10) - 이만큼 실패하면 FAILED로 남고 운영 실패 작업(O-07·O-08)에서 확인
    public static final int MAX_ATTEMPTS = 3;
    // 재시도 간격 - 실패한 시각부터 1번째 실패 뒤 1분, 2번째 실패 뒤 5분
    public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    // 발송 대상인지 - 보낸 것·건너뛴 것·한도를 넘긴 것은 다시 보내지 않음
    public boolean isDeliverable() {
        return (sendStatus == NotificationSendStatus.PENDING || sendStatus == NotificationSendStatus.FAILED)
                && attemptCount < MAX_ATTEMPTS;
    }

    // 보냄 - 시도 횟수에 포함
    public void markSent() {
        attemptCount++;
        sendStatus = NotificationSendStatus.SENT;
        nextAttemptAt = null;
    }

    // 못 보냄 - 시도 횟수가 한도 전이면 실패한 시각부터 간격(RETRY_DELAYS)이 지난 뒤 다시 (명세 5.10)
    // 생성 시각이 아니라 실패한 시각 기준 - 큐에서 오래 기다린 알림도 재시도 사이에 간격을 둠
    public void markFailed(Instant now) {
        attemptCount++;
        sendStatus = NotificationSendStatus.FAILED;
        nextAttemptAt = attemptCount < MAX_ATTEMPTS ? now.plus(RETRY_DELAYS.get(attemptCount - 1)) : null;
    }

    // 보내기 직전에 보니 보낼 필요가 없어짐 - 취소된 예약의 확정 알림, 끝난 제안, 꺼 둔 유형 등 (시도 아님)
    public void markSkipped() {
        sendStatus = NotificationSendStatus.SKIPPED;
    }
}
