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

import java.time.Instant;

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
}
