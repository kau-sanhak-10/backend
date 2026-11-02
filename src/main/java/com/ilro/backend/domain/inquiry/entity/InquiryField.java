package com.ilro.backend.domain.inquiry.entity;

import com.ilro.backend.domain.ai.entity.AiDecision;
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

// 접수 항목 값과 출처·확인 여부 - 수정하면 새 행을 만들고 이전 행은 isCurrent=false로 남김 (B14)
@Getter
@Entity
@Table(name = "inquiry_field")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InquiryField extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    // manufacturer / model / boiler_type / error_code / symptom 등 (목록은 AI 규칙과 함께 확정)
    @Column(nullable = false, length = 30)
    private String fieldKey;

    // 미확인이면 null
    private String fieldValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryFieldSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryFieldStatus status;

    // 근거 사진 (source가 PHOTO일 때 필수)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id")
    private Photo photo;

    // 이 값을 만든 AI 호출 (O10)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_decision_id")
    private AiDecision aiDecision;

    // 근거 메시지 - 고객 설명에서 나온 값일 때 (같은 문의의 메시지만)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    // 사장님 확인이 필요한 이유 (C05)
    @Column(length = 100)
    private String unconfirmedReason;

    // 문의·항목별 현재값은 1개
    @Column(nullable = false)
    private boolean isCurrent = true;

    @Builder
    private InquiryField(Inquiry inquiry, String fieldKey, String fieldValue, InquiryFieldSource source,
                         InquiryFieldStatus status, Photo photo, AiDecision aiDecision, Message message,
                         String unconfirmedReason) {
        this.inquiry = inquiry;
        this.fieldKey = fieldKey;
        this.fieldValue = fieldValue;
        this.source = source;
        this.status = status;
        this.photo = photo;
        this.aiDecision = aiDecision;
        this.message = message;
        this.unconfirmedReason = unconfirmedReason;
    }
}
