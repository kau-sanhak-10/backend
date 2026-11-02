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

// 사람 응대 요청 (직접 상담·전화 회신) - 진행 중 요청은 문의당 1건
@Getter
@Entity
@Table(name = "handoff_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HandoffRequest extends BaseCreatedEntity {

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
}
