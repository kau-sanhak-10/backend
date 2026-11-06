package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.infra.ai.AnalyzeRequest;
import com.ilro.backend.infra.ai.AnalyzeResult;
import com.ilro.backend.infra.ai.AnalyzeResult.Evidence;
import com.ilro.backend.infra.ai.AnalyzeResult.Fact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

// AI 결과 규칙 검사 (명세 6.4) - 형식은 맞아도 이 요청에 맞지 않으면 적용하지 않음
class AiResultValidatorTest {

    private static final long CUSTOMER_MSG = 11L;
    private static final long AI_MSG = 12L;
    private static final long OLD_MSG = 5L;
    private static final long OLD_PHOTO = 40L;

    // 고객 메시지 11, AI 답변 12, 기존 항목의 근거 메시지 5·사진 40
    private static final AnalyzeRequest REQUEST = new AnalyzeRequest("req-1", 7L, 3L, 4, HandlerMode.AI,
            new AnalyzeRequest.Business(List.of(WorkCategory.REPAIR), true),
            List.of(new AnalyzeRequest.Field("model", "NCB-500", InquiryFieldSource.PHOTO,
                            InquiryFieldStatus.CONFIRMED, null, OLD_PHOTO, null),
                    new AnalyzeRequest.Field("manufacturer", "경동", InquiryFieldSource.CUSTOMER,
                            InquiryFieldStatus.CONFIRMED, null, null, OLD_MSG)),
            Map.of(),
            List.of(new AnalyzeRequest.MessageItem(CUSTOMER_MSG, MessageSender.CUSTOMER, "물이 새요",
                            OffsetDateTime.now()),
                    new AnalyzeRequest.MessageItem(AI_MSG, MessageSender.AI, "확인할게요", OffsetDateTime.now())),
            List.of(), false);

    @Test
    @DisplayName("요청에 맞는 결과 - 이번 고객 메시지·기존 항목의 근거를 쓴 사실은 통과")
    void valid() {
        AnalyzeResult result = result(List.of(
                fact("symptom", "물이 새요", "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG, null),
                fact("manufacturer", "경동", "CUSTOMER", "CONFIRMED", null, OLD_MSG, null),
                fact("model", null, "PHOTO", "UNCONFIRMED", "PHOTO_UNREADABLE", null, OLD_PHOTO)));

        assertThat(AiResultValidator.violation(REQUEST, result)).isEmpty();
    }

    @Test
    @DisplayName("다른 요청의 결과 - 요청 ID·문의·입력 버전 중 하나라도 다르면 위반")
    void otherRequest() {
        assertThat(violation(r -> copy(r, "req-2", r.inquiryId(), r.inputVersion()))).hasValue(
                "requestId·inquiryId·inputVersion");
        assertThat(violation(r -> copy(r, r.requestId(), 8L, r.inputVersion()))).isPresent();
        assertThat(violation(r -> copy(r, r.requestId(), r.inquiryId(), 3L))).isPresent();
    }

    @Test
    @DisplayName("값 목록 밖의 판단 - 행동·규칙 이유·판단 주체·작업 종류·신뢰도·답변")
    void outOfContract() {
        AnalyzeResult base = result(List.of());
        assertThat(AiResultValidator.violation(REQUEST, with(base, "ASK_OWNER", "DONE", "RULE", "확인",
                base.workCategory(), base.confidence()))).hasValue("nextAction");
        assertThat(AiResultValidator.violation(REQUEST, with(base, "COMPLETE", "UNKNOWN", "RULE", "확인",
                base.workCategory(), base.confidence()))).hasValue("nextAction");
        assertThat(AiResultValidator.violation(REQUEST, with(base, "COMPLETE", "DONE", "LLM", "확인",
                base.workCategory(), base.confidence()))).hasValue("decidedBy");
        assertThat(AiResultValidator.violation(REQUEST, with(base, "COMPLETE", "DONE", "RULE", " ",
                base.workCategory(), base.confidence()))).hasValue("reply.content");
        assertThat(AiResultValidator.violation(REQUEST, with(base, "COMPLETE", "DONE", "RULE", "확인",
                new AnalyzeResult.WorkCategoryDecision("BOILER", "CONFIRMED"), base.confidence())))
                .hasValue("workCategory");
        assertThat(AiResultValidator.violation(REQUEST, with(base, "COMPLETE", "DONE", "RULE", "확인",
                base.workCategory(), new BigDecimal("1.2")))).hasValue("confidence");
    }

    @Test
    @DisplayName("사실 위반 - 모르는 항목·같은 항목 두 번·값 없는 확인·이유 없는 미확인·칸 길이 초과")
    void factShape() {
        assertThat(factViolation(fact("address", "서울", "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG, null)))
                .hasValue("facts[0].fieldKey");
        assertThat(AiResultValidator.violation(REQUEST, result(List.of(
                fact("symptom", "A", "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG, null),
                fact("symptom", "B", "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG, null)))))
                .hasValue("facts[1].fieldKey");
        assertThat(factViolation(fact("symptom", " ", "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG, null)))
                .hasValue("facts[0].value");
        assertThat(factViolation(fact("error_code", "E".repeat(21), "CUSTOMER", "CONFIRMED", null, CUSTOMER_MSG,
                null))).hasValue("facts[0].value");
        assertThat(factViolation(fact("error_code", null, "CUSTOMER", "UNCONFIRMED", null, CUSTOMER_MSG, null)))
                .hasValue("facts[0].unconfirmedReason");
        assertThat(factViolation(fact("error_code", null, "CUSTOMER", "MAYBE", "NOT_PROVIDED", CUSTOMER_MSG, null)))
                .hasValue("facts[0].status");
    }

    @Test
    @DisplayName("근거 위반 - 사장님 출처·AI 답변 근거·보내지 않은 메시지·사진")
    void factEvidence() {
        assertThat(factViolation(fact("symptom", "A", "OWNER", "CONFIRMED", null, CUSTOMER_MSG, null)))
                .hasValue("facts[0].source·evidence");
        assertThat(factViolation(fact("symptom", "A", "CUSTOMER", "CONFIRMED", null, AI_MSG, null)))
                .hasValue("facts[0].source·evidence");
        assertThat(factViolation(fact("symptom", "A", "CUSTOMER", "CONFIRMED", null, 99L, null)))
                .hasValue("facts[0].source·evidence");
        assertThat(factViolation(fact("model", "A", "PHOTO", "CONFIRMED", null, null, 41L)))
                .hasValue("facts[0].source·evidence");
        assertThat(factViolation(fact("model", "A", "PHOTO", "CONFIRMED", null, CUSTOMER_MSG, OLD_PHOTO)))
                .hasValue("facts[0].source·evidence");
        assertThat(AiResultValidator.violation(REQUEST, result(List.of(
                new Fact("symptom", "A", "CUSTOMER", "CONFIRMED", null, null))))).isPresent();
    }

    private static Optional<String> factViolation(Fact fact) {
        return AiResultValidator.violation(REQUEST, result(List.of(fact)));
    }

    private static Optional<String> violation(UnaryOperator<AnalyzeResult> change) {
        return AiResultValidator.violation(REQUEST, change.apply(result(List.of())));
    }

    private static Fact fact(String key, String value, String source, String status, String reason, Long messageId,
                             Long photoId) {
        return new Fact(key, value, source, status, reason, new Evidence(messageId, photoId));
    }

    private static AnalyzeResult result(List<Fact> facts) {
        return new AnalyzeResult("req-1", 7L, 4L, "r1", "p1", "RULE", null, new AnalyzeResult.Usage(1, 0, 0), facts,
                List.of(), List.of(), new AnalyzeResult.WorkCategoryDecision("REPAIR", "CONFIRMED"),
                new AnalyzeResult.Safety(false, List.of()), "ANSWER",
                new AnalyzeResult.NextAction("COMPLETE", "DONE", List.of(), "ok"), new AnalyzeResult.Reply("확인"),
                new BigDecimal("0.9"));
    }

    private static AnalyzeResult copy(AnalyzeResult r, String requestId, Long inquiryId, Long inputVersion) {
        return new AnalyzeResult(requestId, inquiryId, inputVersion, r.ruleVersion(), r.promptVersion(),
                r.decidedBy(), r.modelName(), r.usage(), r.facts(), r.conflicts(), r.interpretations(),
                r.workCategory(), r.safety(), r.intent(), r.nextAction(), r.reply(), r.confidence());
    }

    private static AnalyzeResult with(AnalyzeResult r, String action, String ruleReason, String decidedBy,
                                      String reply, AnalyzeResult.WorkCategoryDecision category,
                                      BigDecimal confidence) {
        return new AnalyzeResult(r.requestId(), r.inquiryId(), r.inputVersion(), r.ruleVersion(), r.promptVersion(),
                decidedBy, r.modelName(), r.usage(), r.facts(), r.conflicts(), r.interpretations(), category,
                r.safety(), r.intent(), new AnalyzeResult.NextAction(action, ruleReason, List.of(), "ok"),
                new AnalyzeResult.Reply(reply), confidence);
    }
}
