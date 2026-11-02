package com.ilro.backend.domain.ai.entity;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.Message;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

// AI 호출·판단 기록 1건 - 모델 비교·오류 사례 검토용 (O10)
@Getter
@Entity
@Table(name = "ai_decision")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDecision extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    // 이 판단을 만든 고객 메시지
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiStage stage;

    // LLM 모델 ID, decidedBy가 RULE이면 null
    @Column(length = 100)
    private String modelName;

    @Column(length = 20)
    private String promptVersion;

    // LLM에 보낸 대화·수집 상태
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String inputSnapshot;

    // LLM 원본 응답
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String outputRaw;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AiIntent intent;

    // 확신도 0.00 ~ 1.00
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    // SUCCESS면 필수, 실패·형식 오류·오래된 결과는 null 가능
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AiAction action;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RuleReason ruleReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DecidedBy decidedBy;

    private Integer latencyMs;

    private Integer inputTokens;

    private Integer outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiDecisionStatus status;

    // 실패 사유 - 인증정보·불필요한 고객 원문 제외 (O07)
    @Column(length = 500)
    private String errorMessage;

    // 적용한 업무 규칙(YAML) 버전·커밋, 규칙 원본은 GitHub
    @Column(length = 50)
    private String ruleVersion;

    // 요청 때의 Inquiry.aiInputSeq - 결과가 올 때 순번이 더 올랐으면 적용하지 않고 STALE
    private Long inputVersion;

    // AI 요청 ID - 같은 요청의 결과는 한 번만 적용
    @Column(length = 64)
    private String requestId;

    @Builder
    private AiDecision(Inquiry inquiry, Message message, AiStage stage, String modelName, String promptVersion,
                       String inputSnapshot, String outputRaw, AiIntent intent, BigDecimal confidence,
                       AiAction action, RuleReason ruleReason, DecidedBy decidedBy, Integer latencyMs,
                       Integer inputTokens, Integer outputTokens, AiDecisionStatus status, String errorMessage,
                       String ruleVersion, Long inputVersion, String requestId) {
        this.inquiry = inquiry;
        this.message = message;
        this.stage = stage;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.inputSnapshot = inputSnapshot;
        this.outputRaw = outputRaw;
        this.intent = intent;
        this.confidence = confidence;
        this.action = action;
        this.ruleReason = ruleReason;
        this.decidedBy = decidedBy;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.status = status;
        this.errorMessage = errorMessage;
        this.ruleVersion = ruleVersion;
        this.inputVersion = inputVersion;
        this.requestId = requestId;
    }
}
