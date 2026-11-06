package com.ilro.backend.domain.ai.service;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiIntent;
import com.ilro.backend.domain.ai.entity.AiStage;
import com.ilro.backend.domain.ai.entity.DecidedBy;
import com.ilro.backend.domain.ai.entity.RuleReason;
import com.ilro.backend.domain.ai.repository.AiDecisionRepository;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.inquiry.repository.InquiryFieldRepository;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.inquiry.repository.MessageRepository;
import com.ilro.backend.domain.inquiry.repository.PhotoRepository;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.domain.notification.entity.Notification;
import com.ilro.backend.domain.notification.entity.NotificationChannel;
import com.ilro.backend.domain.notification.entity.NotificationEventType;
import com.ilro.backend.domain.notification.entity.NotificationRecipient;
import com.ilro.backend.domain.notification.entity.NotificationRefType;
import com.ilro.backend.domain.notification.repository.NotificationRepository;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.ai.AiCallResult;
import com.ilro.backend.infra.ai.AnalyzeRequest;
import com.ilro.backend.infra.ai.AnalyzeResult;
import com.ilro.backend.infra.ai.AnalyzeResult.Evidence;
import com.ilro.backend.infra.ai.AnalyzeResult.Fact;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// AI 분석 작업의 DB 읽기·쓰기 - 단계마다 따로 트랜잭션 (AI 답을 기다리는 동안 문의 행을 잡고 있지 않음)
// REQUIRES_NEW: 고객 요청이 커밋된 직후(afterCommit) 같은 스레드에서 불려도 끝난 트랜잭션에 끼지 않게
@Component
@RequiredArgsConstructor
public class AiAnalysisStore {

    // 한 번에 보내는 최근 대화 수
    private static final int MESSAGE_LIMIT = 100;
    // AI 계약에 있는 미확인 이유 - 다른 값은 이유 없이 보냄 (AI 서버는 모르는 값이 오면 요청 전체를 거절)
    private static final Set<String> AI_UNCONFIRMED_REASONS =
            Set.of("NOT_PROVIDED", "PHOTO_UNREADABLE", "CONFLICT", "CUSTOMER_UNSURE", "ASK_LIMIT_REACHED");
    private static final List<AiDecisionStatus> FAILURES =
            List.of(AiDecisionStatus.FAILED, AiDecisionStatus.TIMEOUT, AiDecisionStatus.INVALID_OUTPUT);
    // 보완 질문으로 세는 행동
    private static final Set<AiAction> ASKING = EnumSet.of(AiAction.ASK_MORE_INFO, AiAction.REQUEST_IMAGE);

    private final InquiryRepository inquiryRepository;
    private final InquiryAccess inquiryAccess;
    private final MessageRepository messageRepository;
    private final PhotoRepository photoRepository;
    private final InquiryFieldRepository fieldRepository;
    private final AiDecisionRepository decisionRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final NotificationRepository notificationRepository;
    private final JsonMapper jsonMapper;

    // 이번에 보낼 요청과 기록에 필요한 값
    // failedAttempts: 이 입력 버전으로 이미 실패한 호출 수 (서버가 다시 켜지기 전 시도 포함)
    public record Snapshot(AnalyzeRequest request, String requestJson, Long lastCustomerMessageId,
                           int failedAttempts) {

        Long inquiryId() {
            return request.inquiryId();
        }

        long inputVersion() {
            return request.inputVersion();
        }
    }

    // 결과를 적용하거나 실패를 기록한 뒤의 문의
    public enum State {
        CURRENT, // 이 입력 버전이 아직 최신이고 AI가 답하는 중 - 다시 불러도 됨
        NEW_INPUT, // 새 입력이 들어옴 - 이전 작업은 그만두고 최신 입력으로 다시
        DONE // 적용했거나 사장님 응대·단계 변경으로 더 할 일 없음
    }

    // 분석할 게 없으면 빈 값: AI가 답하는 상담이 아님 · 고객 메시지 없음 · 마지막 고객 메시지에 이미 답함
    // 방문정보·접수정보만 바뀐 경우엔 새로 답하지 않음 (분석 중이던 작업만 최신 입력으로 다시 함)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Snapshot> prepare(Long inquiryId) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId).filter(this::isAnswering).orElse(null);
        if (inquiry == null) {
            return Optional.empty();
        }
        long inputVersion = inquiry.getAiInputSeq();
        Message lastCustomer = messageRepository
                .findFirstByInquiryIdAndSenderOrderByIdDesc(inquiryId, MessageSender.CUSTOMER).orElse(null);
        boolean answered = lastCustomer == null || decisionRepository
                .findFirstByInquiryIdAndStageAndStatusOrderByIdDesc(inquiryId, AiStage.DECIDE, AiDecisionStatus.SUCCESS)
                .filter(applied -> Objects.equals(applied.getInputVersion(), inputVersion)
                        || (applied.getMessage() != null && applied.getMessage().getId() >= lastCustomer.getId()))
                .isPresent();
        if (answered) {
            return Optional.empty();
        }

        // 같은 입력을 다시 부르는 것은 같은 요청 - 처음 요청 ID를 이어서 씀
        List<AiDecision> failures = decisionRepository
                .findByInquiryIdAndInputVersionAndStatusInOrderByIdAsc(inquiryId, inputVersion, FAILURES);
        String requestId = failures.isEmpty() ? UUID.randomUUID().toString() : failures.getFirst().getRequestId();
        // 사진 주소(리사이즈본의 짧은 유효기간 주소)는 사진 업로드(BE-06)가 생기면 함께 보냄
        AnalyzeRequest request = new AnalyzeRequest(requestId, inquiryId, inquiry.getOwner().getId(), inputVersion,
                HandlerMode.AI, business(inquiry.getOwner()), fields(inquiryId), askCounts(inquiryId),
                messages(inquiryId), List.of(), inquiry.isSafetyFlag());
        return Optional.of(new Snapshot(request, jsonMapper.writeValueAsString(request), lastCustomer.getId(),
                failures.size()));
    }

    // 결과 적용 (명세 6.1 4단계) - 문의 행을 잠그고 입력 버전·응대 주체를 다시 확인한 뒤 한 트랜잭션으로 저장
    // 확인과 저장 사이에 새 입력이 들어와도 오래된 결과가 덮어쓰지 않음
    // AI 입력 순번은 올리지 않음 (자기 결과 때문에 다시 분석하지 않게), version은 1 올림
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public State apply(Snapshot snapshot, AiCallResult call) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(snapshot.inquiryId()).orElseThrow();
        if (decisionRepository.existsByInquiryIdAndRequestIdAndStatus(inquiry.getId(),
                snapshot.request().requestId(), AiDecisionStatus.SUCCESS)) {
            return State.DONE;
        }
        State state = stateOf(inquiry, snapshot);
        if (state != State.CURRENT) {
            decisionRepository.save(decision(inquiry, snapshot, call, AiDecisionStatus.STALE));
            return state;
        }

        AnalyzeResult result = call.result();
        int versionBefore = inquiry.getVersion();
        AiDecision decision = decisionRepository.save(decision(inquiry, snapshot, call, AiDecisionStatus.SUCCESS));
        applyFacts(inquiry, decision, result.facts());
        applyWorkCategory(inquiry, result.workCategory());
        // 고객 화면은 메시지 가져오기로 받으므로 저장되면 전달 완료
        Message reply = Message.builder()
                .inquiry(inquiry)
                .sender(MessageSender.AI)
                .aiDecision(decision)
                .content(result.reply().content())
                .build();
        reply.markSent();
        messageRepository.save(reply);
        moveStatus(inquiry, result, snapshot.inputVersion());
        inquiryAccess.bumpVersion(inquiry, versionBefore);
        return State.DONE;
    }

    // 실패한 호출 기록 - 허위 판단값 없이 (모델명·할 일 비움, 명세 6.4)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public State recordFailure(Snapshot snapshot, AiCallResult call) {
        Inquiry inquiry = inquiryRepository.findById(snapshot.inquiryId()).orElseThrow();
        AiDecisionStatus status = switch (call.outcome()) {
            case TIMEOUT -> AiDecisionStatus.TIMEOUT;
            case INVALID_OUTPUT -> AiDecisionStatus.INVALID_OUTPUT;
            default -> AiDecisionStatus.FAILED;
        };
        decisionRepository.save(AiDecision.builder()
                .inquiry(inquiry)
                .message(messageRepository.getReferenceById(snapshot.lastCustomerMessageId()))
                .stage(AiStage.DECIDE)
                .inputSnapshot(snapshot.requestJson())
                .outputRaw(call.rawBody())
                .decidedBy(DecidedBy.EXTERNAL_API)
                .latencyMs(call.latencyMs())
                .status(status)
                .errorMessage(cut(call.errorCode(), 500))
                .inputVersion(snapshot.inputVersion())
                .requestId(snapshot.request().requestId())
                .build());
        return stateOf(inquiry, snapshot);
    }

    // 같은 입력의 호출 한도를 다 썼거나 다시 불러도 소용없는 실패 → 사장님 확인 + 알림 (명세 6.4)
    // 그사이 새 입력이 왔으면 그대로 두고 최신 입력으로 다시
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public State giveUp(Snapshot snapshot) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(snapshot.inquiryId()).orElseThrow();
        State state = stateOf(inquiry, snapshot);
        if (state != State.CURRENT) {
            return state;
        }
        int versionBefore = inquiry.getVersion();
        needsOwnerCheck(inquiry, snapshot.inputVersion());
        inquiryAccess.bumpVersion(inquiry, versionBefore);
        return State.DONE;
    }

    private boolean isAnswering(Inquiry inquiry) {
        return !inquiry.isDeleted() && inquiry.isAiAnswering() && inquiry.getOwner().isServingCustomers();
    }

    private State stateOf(Inquiry inquiry, Snapshot snapshot) {
        if (!isAnswering(inquiry)) {
            return State.DONE;
        }
        return inquiry.getAiInputSeq() == snapshot.inputVersion() ? State.CURRENT : State.NEW_INPUT;
    }

    private AiDecision decision(Inquiry inquiry, Snapshot snapshot, AiCallResult call, AiDecisionStatus status) {
        AnalyzeResult result = call.result();
        DecidedBy decidedBy = DecidedBy.valueOf(result.decidedBy());
        AnalyzeResult.Usage usage = result.usage();
        return AiDecision.builder()
                .inquiry(inquiry)
                .message(messageRepository.getReferenceById(snapshot.lastCustomerMessageId()))
                .stage(AiStage.DECIDE)
                // 서버 규칙이 정한 판단엔 모델명을 두지 않음 (ck_ai_decision_rule_model) - 원본은 output_raw에
                .modelName(decidedBy == DecidedBy.RULE ? null : cut(result.modelName(), 100))
                .promptVersion(cut(result.promptVersion(), 20))
                .ruleVersion(cut(result.ruleVersion(), 50))
                .inputSnapshot(snapshot.requestJson())
                .outputRaw(call.rawBody())
                .intent(Arrays.stream(AiIntent.values())
                        .filter(intent -> intent.name().equals(result.intent())).findFirst().orElse(null))
                .confidence(result.confidence() == null ? null : result.confidence().setScale(2, RoundingMode.HALF_UP))
                .action(AiAction.valueOf(result.nextAction().action()))
                .ruleReason(result.nextAction().ruleReason() == null ? null
                        : RuleReason.valueOf(result.nextAction().ruleReason()))
                .decidedBy(decidedBy)
                .latencyMs(usage != null && usage.latencyMs() != null ? usage.latencyMs() : call.latencyMs())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .status(status)
                .inputVersion(snapshot.inputVersion())
                .requestId(snapshot.request().requestId())
                .build();
    }

    // 사실(facts)만 접수 항목에 - 출처는 AI 제안(CUSTOMER·PHOTO), 근거 메시지·사진과 이 판단을 함께 남김
    // 사장님이 정한 값은 덮어쓰지 않고, 내용이 그대로인 항목은 건너뜀 (명세 6.3·6.4)
    private void applyFacts(Inquiry inquiry, AiDecision decision, List<Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Map<String, InquiryField> current = fieldRepository.findCurrent(inquiry.getId()).stream()
                .collect(Collectors.toMap(InquiryField::getFieldKey, Function.identity()));
        List<InquiryField> rows = new ArrayList<>();
        for (Fact fact : facts) {
            InquiryField before = current.get(fact.fieldKey());
            if (before != null && before.getSource() == InquiryFieldSource.OWNER) {
                continue;
            }
            InquiryFieldSource source = InquiryFieldSource.valueOf(fact.source());
            InquiryFieldStatus status = InquiryFieldStatus.valueOf(fact.status());
            String reason = status == InquiryFieldStatus.UNCONFIRMED ? fact.unconfirmedReason() : null;
            if (before != null && before.getSource() == source && before.getStatus() == status
                    && Objects.equals(before.getFieldValue(), fact.value())
                    && Objects.equals(before.getUnconfirmedReason(), reason)) {
                continue;
            }
            Evidence evidence = fact.evidence();
            rows.add(InquiryField.builder()
                    .inquiry(inquiry)
                    .fieldKey(fact.fieldKey())
                    .fieldValue(fact.value())
                    .source(source)
                    .status(status)
                    .unconfirmedReason(reason)
                    .photo(evidence.photoId() == null ? null : photoRepository.getReferenceById(evidence.photoId()))
                    .message(evidence.messageId() == null ? null
                            : messageRepository.getReferenceById(evidence.messageId()))
                    .aiDecision(decision)
                    .build());
        }
        if (rows.isEmpty()) {
            return;
        }
        fieldRepository.retireCurrent(inquiry.getId(), rows.stream().map(InquiryField::getFieldKey).toList());
        fieldRepository.saveAll(rows);
        for (InquiryField row : rows) {
            inquiry.applyField(InquiryFieldKey.fromKey(row.getFieldKey()).orElseThrow(), row.getFieldValue());
        }
    }

    // 고객이 고른 작업이 없을 때만 AI가 확인한 작업으로 채움 - 업체가 제공하는 작업만 (작업시간은 업체 설정)
    private void applyWorkCategory(Inquiry inquiry, AnalyzeResult.WorkCategoryDecision category) {
        if (inquiry.getWorkType() != null || category == null || category.value() == null
                || !InquiryFieldStatus.CONFIRMED.name().equals(category.status())) {
            return;
        }
        workTypeRepository.findByOwnerId(inquiry.getOwner().getId()).stream()
                .filter(workType -> workType.isActive() && workType.getWorkCategory().name().equals(category.value()))
                .findFirst()
                .ifPresent(inquiry::changeWorkType);
    }

    // 다음 행동에 따른 진행 단계 (명세 2.1·6.4) - 판단만 반영하고 예약 조건은 예약 API가 다시 검사
    private void moveStatus(Inquiry inquiry, AnalyzeResult result, long inputVersion) {
        // 안전 신호: 자동 예약·AI 재개 차단, 사장님 확인, 알림 우선
        if (Boolean.TRUE.equals(result.safety().flag())) {
            inquiry.flagSafety();
            needsOwnerCheck(inquiry, inputVersion);
            return;
        }
        switch (AiAction.valueOf(result.nextAction().action())) {
            case CHECK_SCHEDULE -> inquiry.changeStatus(InquiryStatus.SCHEDULING);
            case ASK_MORE_INFO, REQUEST_IMAGE -> inquiry.changeStatus(InquiryStatus.COLLECTING);
            // 정리 완료(COMPLETE)도 자동 예약이 아니면 사장님 확인으로 넘김 (명세 7.2-6, REQUESTED 예약 안 씀)
            case ESCALATE_TO_OWNER, COMPLETE -> needsOwnerCheck(inquiry, inputVersion);
            case UNSERVICEABLE -> inquiry.changeStatus(InquiryStatus.UNSERVICEABLE);
            case ANSWER_SIDE_QUESTION -> {
            }
        }
    }

    // 사장님 확인 필요 + 사장님 알림 (입력 버전마다 하나)
    private void needsOwnerCheck(Inquiry inquiry, long inputVersion) {
        inquiry.changeStatus(InquiryStatus.OWNER_CHECK);
        String dedupeKey = "NEEDS_CHECK:INQUIRY:" + inquiry.getId() + ":" + inputVersion;
        if (!notificationRepository.existsByDedupeKey(dedupeKey)) {
            notificationRepository.save(Notification.builder()
                    .owner(inquiry.getOwner())
                    .recipient(NotificationRecipient.OWNER)
                    .eventType(NotificationEventType.NEEDS_CHECK)
                    .refType(NotificationRefType.INQUIRY)
                    .refId(inquiry.getId())
                    .channel(NotificationChannel.IN_APP)
                    .dedupeKey(dedupeKey)
                    .build());
        }
    }

    private AnalyzeRequest.Business business(Owner owner) {
        return new AnalyzeRequest.Business(workTypeRepository.findByOwnerId(owner.getId()).stream()
                .filter(OwnerWorkType::isActive)
                .map(OwnerWorkType::getWorkCategory)
                .sorted()
                .toList(), owner.isAutoBookingEnabled());
    }

    private List<AnalyzeRequest.Field> fields(Long inquiryId) {
        return fieldRepository.findCurrent(inquiryId).stream()
                .filter(field -> InquiryFieldKey.fromKey(field.getFieldKey()).isPresent())
                .map(field -> new AnalyzeRequest.Field(field.getFieldKey(), field.getFieldValue(), field.getSource(),
                        field.getStatus(),
                        field.getStatus() == InquiryFieldStatus.UNCONFIRMED
                                && AI_UNCONFIRMED_REASONS.contains(field.getUnconfirmedReason())
                                ? field.getUnconfirmedReason() : null,
                        field.getPhoto() == null ? null : field.getPhoto().getId(),
                        field.getMessage() == null ? null : field.getMessage().getId()))
                .toList();
    }

    // 적용된 판단의 보완 질문 대상(nextAction.targetFieldKeys)을 항목별로 셈
    private Map<String, Integer> askCounts(Long inquiryId) {
        Map<String, Integer> counts = new HashMap<>();
        for (AiDecision decision : decisionRepository.findByInquiryIdAndStageAndStatusAndActionIn(inquiryId,
                AiStage.DECIDE, AiDecisionStatus.SUCCESS, ASKING)) {
            if (decision.getOutputRaw() == null) {
                continue;
            }
            try {
                for (JsonNode key : jsonMapper.readTree(decision.getOutputRaw())
                        .path("result").path("nextAction").path("targetFieldKeys")) {
                    if (key.isString()) {
                        counts.merge(key.asString(), 1, Integer::sum);
                    }
                }
            } catch (JacksonException e) {
                // 읽지 못한 기록은 세지 않음
            }
        }
        return counts;
    }

    private List<AnalyzeRequest.MessageItem> messages(Long inquiryId) {
        List<Message> recent = new ArrayList<>(
                messageRepository.findByInquiryIdOrderByIdDesc(inquiryId, Limit.of(MESSAGE_LIMIT)));
        return recent.reversed().stream()
                .map(message -> new AnalyzeRequest.MessageItem(message.getId(), message.getSender(),
                        message.getContent() == null ? "" : message.getContent(),
                        KstTime.of(message.getCreatedAt())))
                .toList();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
