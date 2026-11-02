package com.ilro.backend.domain.notification.entity;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 사장님 브라우저 Web Push 구독
@Getter
@Entity
@Table(name = "push_subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscription extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Column(nullable = false, length = 500)
    private String endpoint;

    // 구독 공개키
    @Column(nullable = false)
    private String p256dh;

    // 구독 인증 secret
    @Column(nullable = false)
    private String auth;

    // 브라우저 정보
    private String userAgent;

    @Builder
    private PushSubscription(Owner owner, String endpoint, String p256dh, String auth, String userAgent) {
        this.owner = owner;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.userAgent = userAgent;
    }
}
