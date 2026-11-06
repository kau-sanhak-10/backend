package com.ilro.backend.domain.inquiry.entity;

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
import java.util.EnumSet;
import java.util.Set;

// 사람 응대 요청 (직접 상담·전화 회신) - 진행 중 요청은 문의당 1건
@Getter
@Entity
@Table(name = "handoff_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HandoffRequest extends BaseCreatedEntity {

    // 진행 중 요청 - 문의당 1건 (uq_handoff_request_active)
    public static final Set<HandoffStatus> ACTIVE = EnumSet.of(HandoffStatus.WAITING, HandoffStatus.IN_PROGRESS);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HandoffRequestType requestType;

    // 회신받을 이름·휴대전화 (CALLBACK일 때)
    @Column(length = 50)
    private String name;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HandoffStatus status = HandoffStatus.WAITING;

    private Instant resolvedAt;

    @Builder
    private HandoffRequest(Inquiry inquiry, HandoffRequestType requestType, String name, String phone) {
        this.inquiry = inquiry;
        this.requestType = requestType;
        this.name = name;
        this.phone = phone;
    }

    // 대기 중 직접 상담을 전화 회신으로 바꿈 (명세 5.5) - 같은 요청을 이어 씀
    public void switchToCallback(String name, String phone) {
        this.requestType = HandoffRequestType.CALLBACK;
        this.name = name;
        this.phone = phone;
    }

    // 처리 상태 (WAITING → IN_PROGRESS → DONE) - 끝내면 끝낸 시각을 남김
    public void changeStatus(HandoffStatus status, Instant now) {
        this.status = status;
        this.resolvedAt = status == HandoffStatus.DONE ? now : null;
    }
}
