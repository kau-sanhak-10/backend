package com.ilro.backend.domain.auth.entity;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
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

// 휴대전화 문자 인증 1회 - createdAt이 재발송 횟수 제한 기준
@Getter
@Entity
@Table(name = "phone_verification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneVerification extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationPurpose purpose;

    // 6자리 인증번호 해시 (원문 저장 금지)
    @Column(nullable = false)
    private String codeHash;

    // 입력 시도 횟수 - 반복 시도 제한
    @Column(nullable = false)
    private int attemptCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationSendStatus sendStatus;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant verifiedAt;

    // 인증 대상 - 같은 번호·목적이라도 대상이 다르면 이 인증을 쓰지 않음
    // CUSTOMER_VERIFY: 그 문의 / OWNER_LOGIN: 그 업체 / INQUIRY_ACCESS·RESERVATION_LOOKUP: 링크의 업체
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_inquiry_id")
    private Inquiry targetInquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_owner_id")
    private Owner targetOwner;

    // IP별 누적 발송 제한 기준
    @Column(length = 45)
    private String requestIp;

    @Builder
    private PhoneVerification(String phone, VerificationPurpose purpose, String codeHash,
                              VerificationSendStatus sendStatus, Instant expiresAt, Inquiry targetInquiry,
                              Owner targetOwner, String requestIp) {
        this.phone = phone;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.sendStatus = sendStatus;
        this.expiresAt = expiresAt;
        this.targetInquiry = targetInquiry;
        this.targetOwner = targetOwner;
        this.requestIp = requestIp;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    // 틀린 입력 1회 기록, 남은 횟수 반환
    public int recordFailedAttempt(int maxAttempts) {
        attemptCount++;
        return Math.max(0, maxAttempts - attemptCount);
    }

    // 성공 - 다시 쓸 수 없음
    public void markVerified(Instant now) {
        this.verifiedAt = now;
    }
}
