package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.DecidedBy;
import com.ilro.backend.domain.ai.entity.RuleReason;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.infra.ai.AnalyzeRequest;
import com.ilro.backend.infra.ai.AnalyzeResult;
import com.ilro.backend.infra.ai.AnalyzeResult.Evidence;
import com.ilro.backend.infra.ai.AnalyzeResult.Fact;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// AI 결과가 이 요청에 맞는지 - 형식은 맞아도 값이 규칙 위반이면 결과 전체를 적용하지 않음 (명세 6.4, INVALID_OUTPUT)
// 없는 항목·허용 안 된 행동·OWNER 출처·요청에 없는 메시지·사진 근거 등. 위반 내용엔 칸 이름만 남김 (고객 원문 없음)
final class AiResultValidator {

    private AiResultValidator() {
    }

    static Optional<String> violation(AnalyzeRequest request, AnalyzeResult result) {
        if (!Objects.equals(result.requestId(), request.requestId())
                || !Objects.equals(result.inquiryId(), request.inquiryId())
                || !Objects.equals(result.inputVersion(), request.inputVersion())) {
            return Optional.of("requestId·inquiryId·inputVersion");
        }
        if (!isOneOf(result.decidedBy(), DecidedBy.class)) {
            return Optional.of("decidedBy");
        }
        AnalyzeResult.NextAction next = result.nextAction();
        if (next == null || !isOneOf(next.action(), AiAction.class)
                || (next.ruleReason() != null && !isOneOf(next.ruleReason(), RuleReason.class))) {
            return Optional.of("nextAction");
        }
        if (result.reply() == null || result.reply().content() == null || result.reply().content().isBlank()) {
            return Optional.of("reply.content");
        }
        if (result.safety() == null || result.safety().flag() == null) {
            return Optional.of("safety.flag");
        }
        if (result.confidence() != null
                && (result.confidence().signum() < 0 || result.confidence().compareTo(BigDecimal.ONE) > 0)) {
            return Optional.of("confidence");
        }
        AnalyzeResult.Usage usage = result.usage();
        if (usage != null && Stream.of(usage.latencyMs(), usage.inputTokens(), usage.outputTokens())
                .anyMatch(value -> value != null && value < 0)) {
            return Optional.of("usage");
        }
        AnalyzeResult.WorkCategoryDecision category = result.workCategory();
        if (category != null && ((category.value() != null && !isOneOf(category.value(), WorkCategory.class))
                || !isOneOf(category.status(), InquiryFieldStatus.class))) {
            return Optional.of("workCategory");
        }
        if (result.conflicts() != null && result.conflicts().stream()
                .anyMatch(conflict -> InquiryFieldKey.fromKey(conflict.fieldKey()).isEmpty())) {
            return Optional.of("conflicts.fieldKey");
        }
        return factViolation(request, result.facts() == null ? List.of() : result.facts());
    }

    // 근거는 이번에 보낸 고객 메시지·사진, 또는 기존 항목이 이미 가진 근거 안에서만
    private static Optional<String> factViolation(AnalyzeRequest request, List<Fact> facts) {
        Set<Long> messageIds = Stream.concat(
                request.messages().stream()
                        .filter(message -> message.sender() == MessageSender.CUSTOMER)
                        .map(AnalyzeRequest.MessageItem::messageId),
                request.fields().stream().map(AnalyzeRequest.Field::messageId).filter(Objects::nonNull))
                .collect(Collectors.toSet());
        Set<Long> photoIds = Stream.concat(
                request.photos().stream().map(AnalyzeRequest.PhotoItem::photoId),
                request.fields().stream().map(AnalyzeRequest.Field::photoId).filter(Objects::nonNull))
                .collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            String at = "facts[" + i + "]";
            Optional<InquiryFieldKey> key = InquiryFieldKey.fromKey(fact.fieldKey());
            if (key.isEmpty() || !seen.add(fact.fieldKey())) {
                return Optional.of(at + ".fieldKey");
            }
            if (!isOneOf(fact.status(), InquiryFieldStatus.class)) {
                return Optional.of(at + ".status");
            }
            boolean confirmed = InquiryFieldStatus.CONFIRMED.name().equals(fact.status());
            if ((confirmed && (fact.value() == null || fact.value().isBlank()))
                    || (fact.value() != null && fact.value().length() > key.get().getMaxLength())) {
                return Optional.of(at + ".value");
            }
            if (!confirmed && (fact.unconfirmedReason() == null || fact.unconfirmedReason().isBlank()
                    || fact.unconfirmedReason().length() > 100)) {
                return Optional.of(at + ".unconfirmedReason");
            }
            Evidence evidence = fact.evidence();
            boolean evidenceOk = evidence != null && switch (String.valueOf(fact.source())) {
                case "CUSTOMER" -> evidence.photoId() == null && messageIds.contains(evidence.messageId());
                case "PHOTO" -> evidence.messageId() == null && photoIds.contains(evidence.photoId());
                // OWNER 등 AI가 만들 수 없는 출처
                default -> false;
            };
            if (!evidenceOk) {
                return Optional.of(at + ".source·evidence");
            }
        }
        return Optional.empty();
    }

    private static <E extends Enum<E>> boolean isOneOf(String value, Class<E> type) {
        return value != null && Arrays.stream(type.getEnumConstants()).anyMatch(constant -> constant.name().equals(value));
    }
}
