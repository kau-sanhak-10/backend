package com.ilro.backend.domain.owner.entity;

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

import java.time.Instant;

// 예약을 받지 않는 시간 (개인 일정 등) - 예약과의 충돌은 서버에서 검사
@Getter
@Entity
@Table(name = "owner_block")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Column(nullable = false)
    private Instant startAt;

    // startAt보다 뒤
    @Column(nullable = false)
    private Instant endAt;

    // 개인 일정 / 전화 업무 등
    @Column(length = 100)
    private String reason;

    @Builder
    private OwnerBlock(Owner owner, Instant startAt, Instant endAt, String reason) {
        this.owner = owner;
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
    }
}
