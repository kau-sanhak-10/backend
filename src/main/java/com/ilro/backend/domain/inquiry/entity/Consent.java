package com.ilro.backend.domain.inquiry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

// 동의 기록 - 대화·사진 수집 전에 먼저 저장 (C01·C06)
// created_at 대신 agreed_at이라 공통 부모를 상속하지 않고 저장 시각을 직접 채움
@Getter
@Entity
@Table(name = "consent")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsentType consentType;

    // 동의한 약관 버전
    @Column(nullable = false, length = 20)
    private String termsVersion;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant agreedAt;

    @Builder
    private Consent(Inquiry inquiry, ConsentType consentType, String termsVersion) {
        this.inquiry = inquiry;
        this.consentType = consentType;
        this.termsVersion = termsVersion;
    }
}
