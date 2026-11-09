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
import com.ilro.backend.infra.ai.AnalyzeResult.Candidate;
import com.ilro.backend.infra.ai.AnalyzeResult.Conflict;
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
        Scope scope = Scope.of(request);
        return factViolation(scope, result.facts() == null ? List.of() : result.facts())
                .or(() -> conflictViolation(scope, result));
    }

    // 근거로 쓸 수 있는 것 - 이번에 보낸 고객 메시지·사진, 또는 기존 항목이 이미 가진 근거
    private record Scope(Set<Long> messageIds, Set<Long> photoIds) {

        static Scope of(AnalyzeRequest request) {
            return new Scope(Stream.concat(
                    request.messages().stream()
                            .filter(message -> message.sender() == MessageSender.CUSTOMER)
                            .map(AnalyzeRequest.MessageItem::messageId),
                    request.fields().stream().map(AnalyzeRequest.Field::messageId).filter(Objects::nonNull))
                    .collect(Collectors.toSet()),
                    Stream.concat(
                            request.photos().stream().map(AnalyzeRequest.PhotoItem::photoId),
                            request.fields().stream().map(AnalyzeRequest.Field::photoId).filter(Objects::nonNull))
                            .collect(Collectors.toSet()));
        }

        // 출처에 맞는 근거 하나 - 고객 설명은 메시지, 사진은 사진
        boolean matches(String source, Evidence evidence) {
            return evidence != null && switch (String.valueOf(source)) {
                case "CUSTOMER" -> evidence.photoId() == null && messageIds.contains(evidence.messageId());
                case "PHOTO" -> evidence.messageId() == null && photoIds.contains(evidence.photoId());
                default -> false;
            };
        }
    }

    // 충돌 (명세 6.3): 아는 항목·항목당 하나·후보 둘 이상, 후보 값은 칸 길이 안
    // 후보 근거는 없어도 되지만(기존 값) 있으면 이 요청 범위 안. 사장님 값 후보는 근거 없이 옴
    // 서버가 미확인 행에 쓸 마지막 새 후보(고객 설명·사진)는 근거가 있어야 함
    // 필수 항목이 충돌 중이면 방문 시간 고르기로 넘기지 않음 (AI 서버 규칙과 같음)
    private static Optional<String> conflictViolation(Scope scope, AnalyzeResult result) {
        List<Conflict> conflicts = result.conflicts() == null ? List.of() : result.conflicts();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < conflicts.size(); i++) {
            Conflict conflict = conflicts.get(i);
            String at = "conflicts[" + i + "]";
            Optional<InquiryFieldKey> key = conflict == null ? Optional.empty()
                    : InquiryFieldKey.fromKey(conflict.fieldKey());
            if (key.isEmpty() || !seen.add(conflict.fieldKey())) {
                return Optional.of(at + ".fieldKey");
            }
            List<Candidate> candidates = conflict.candidates();
            if (candidates == null || candidates.size() < 2 || candidates.stream().anyMatch(candidate ->
                    candidate == null || candidate.value() == null || candidate.value().isBlank()
                            || candidate.value().length() > key.get().getMaxLength()
                            || !isCandidateSource(candidate.source())
                            || (candidate.evidence() != null && !candidateEvidenceOk(scope, candidate)))) {
                return Optional.of(at + ".candidates");
            }
            Optional<Candidate> latest = conflict.latestObserved();
            if (latest.isEmpty() || !scope.matches(latest.get().source(), latest.get().evidence())) {
                return Optional.of(at + ".candidates");
            }
            if (key.get().isRequired() && AiAction.CHECK_SCHEDULE.name().equals(result.nextAction().action())) {
                return Optional.of("nextAction");
            }
        }
        return Optional.empty();
    }

    private static boolean isCandidateSource(String source) {
        return "CUSTOMER".equals(source) || "PHOTO".equals(source) || "OWNER".equals(source);
    }

    // 사장님 값 후보는 그 값의 근거(메시지·사진) 어느 쪽이든 범위 안이면 됨
    private static boolean candidateEvidenceOk(Scope scope, Candidate candidate) {
        if ("OWNER".equals(candidate.source())) {
            return scope.matches("CUSTOMER", candidate.evidence()) || scope.matches("PHOTO", candidate.evidence());
        }
        return scope.matches(candidate.source(), candidate.evidence());
    }

    private static Optional<String> factViolation(Scope scope, List<Fact> facts) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < facts.size(); i++) {
            Fact fact = facts.get(i);
            String at = "facts[" + i + "]";
            // "facts":[null]도 출력 오류로 - 예외로 빠지면 실패 기록·재시도·사장님 확인을 건너뜀 (CodeRabbit 리뷰)
            if (fact == null) {
                return Optional.of(at);
            }
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
            // OWNER 등 AI가 만들 수 없는 출처는 근거와 상관없이 위반
            if (!scope.matches(fact.source(), fact.evidence())) {
                return Optional.of(at + ".source·evidence");
            }
        }
        return Optional.empty();
    }

    private static <E extends Enum<E>> boolean isOneOf(String value, Class<E> type) {
        return value != null && Arrays.stream(type.getEnumConstants()).anyMatch(constant -> constant.name().equals(value));
    }
}
