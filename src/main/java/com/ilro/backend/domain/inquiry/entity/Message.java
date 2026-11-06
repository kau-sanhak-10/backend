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

// 상담 메시지
@Getter
@Entity
@Table(name = "message")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageSender sender;

    // 이 답변을 만든 AI 판단 - sender가 AI일 때만 (O10)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_decision_id")
    private AiDecision aiDecision;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageDeliveryStatus deliveryStatus = MessageDeliveryStatus.PENDING;

    // 클라이언트 생성 ID - 문의 안에서 UNIQUE, 재전송 중복 방지 (C04)
    @Column(length = 64)
    private String clientMsgId;

    @Builder
    private Message(Inquiry inquiry, MessageSender sender, AiDecision aiDecision, String content, String clientMsgId) {
        this.inquiry = inquiry;
        this.sender = sender;
        this.aiDecision = aiDecision;
        this.content = content;
        this.clientMsgId = clientMsgId;
    }

    // 서버에 저장되면 전달 완료 - 고객 메시지, 그리고 고객 화면이 가져가기로 받는 AI 답변
    public void markSent() {
        this.deliveryStatus = MessageDeliveryStatus.SENT;
    }
}
