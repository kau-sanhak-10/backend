package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.ai.service.AiAnalysisRequested;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.inquiry.dto.CloseInquiryResponse;
import com.ilro.backend.domain.inquiry.dto.HandlerChangeResponse;
import com.ilro.backend.domain.inquiry.dto.MessagesResponse.MessageItem;
import com.ilro.backend.domain.inquiry.dto.OwnerHandoffResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerMessageRequest;
import com.ilro.backend.domain.inquiry.dto.UpdateHandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffRequestType;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.HandoffRequestRepository;
import com.ilro.backend.domain.inquiry.repository.MessageRepository;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 사장님 응대 전환 (B-15·B-16, 명세 4.5·6.4) - 직접 응대 시작·사장님 답변·AI 재개·문의 종료·상담 요청 처리
// 모두 문의 행을 잠그고 처리 - 처리 중이던 AI 결과는 응대 주체·입력 순번을 다시 확인하므로 적용되지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerHandlingService {

    private final InquiryAccess inquiryAccess;
    private final MessageRepository messageRepository;
    private final HandoffRequestRepository handoffRepository;
    private final InquiryFieldService fieldService;
    private final ReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthTokenService authTokenService;
    private final Clock clock;

    // created: 새로 저장했으면 true(201), 같은 clientMsgId 재전송이면 false(200)
    public record SentMessage(MessageItem message, boolean created) {
    }

    // 직접 응대 시작 (B-15) - 응대 주체를 OWNER로 바꾼 뒤에만 성공을 돌려줌
    // AI 입력 순번을 올려 처리 중이던 AI 결과를 버림. 대기 중 직접 상담 요청은 처리 중으로
    // 이미 직접 응대 중이면 그대로 200
    @Transactional
    public HandlerChangeResponse takeOver(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.lockOwned(principal, inquiryId);
        inquiryAccess.checkOpen(inquiry);
        int versionBefore = inquiry.getVersion();
        boolean handlerChanged = inquiry.getHandlerMode() != HandlerMode.OWNER;
        if (handlerChanged) {
            inquiry.takeOver();
        }
        Optional<HandoffRequest> waitingChat = activeHandoff(inquiry)
                .filter(handoff -> handoff.getRequestType() == HandoffRequestType.DIRECT_CHAT
                        && handoff.getStatus() == HandoffStatus.WAITING);
        waitingChat.ifPresent(handoff -> handoff.changeStatus(HandoffStatus.IN_PROGRESS, clock.instant()));
        if (handlerChanged) {
            // 응대 주체가 바뀌면 AI 입력 순번도 올림 (명세 6.1)
            inquiryAccess.markInfoChanged(inquiry, versionBefore);
        } else if (waitingChat.isPresent()) {
            inquiryAccess.bumpVersion(inquiry, versionBefore);
        }
        if (handlerChanged || waitingChat.isPresent()) {
            AccessLog.record(principal, "UPDATE", "inquiry.handler", inquiryId);
        }
        return HandlerChangeResponse.from(inquiry);
    }

    // 사장님 답변 (B-15) - 직접 응대 중일 때만 (아니면 409 NOT_IN_OWNER_MODE)
    // 고객 화면은 메시지 가져오기로 받으므로 저장되면 전달 완료
    @Transactional
    public SentMessage sendMessage(AuthPrincipal principal, Long inquiryId, OwnerMessageRequest request) {
        Inquiry inquiry = inquiryAccess.lockOwned(principal, inquiryId);
        // 재전송이면 그사이 상담이 종료됐어도 처음 결과를 돌려줌
        Optional<Message> sent = messageRepository.findByInquiryIdAndClientMsgId(inquiry.getId(),
                request.clientMsgId());
        if (sent.isPresent()) {
            if (sent.get().getSender() != MessageSender.OWNER) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        List.of(new FieldErrorResponse("clientMsgId", "이미 쓰인 메시지 ID예요. 새 ID로 보내 주세요")));
            }
            return new SentMessage(MessageItem.of(sent.get(), List.of()), false);
        }
        inquiryAccess.checkOpen(inquiry);
        if (inquiry.getHandlerMode() != HandlerMode.OWNER) {
            throw new BusinessException(InquiryErrorCode.NOT_IN_OWNER_MODE);
        }
        Message message = Message.builder()
                .inquiry(inquiry)
                .sender(MessageSender.OWNER)
                .content(request.content())
                .clientMsgId(request.clientMsgId())
                .build();
        message.markSent();
        messageRepository.save(message);
        AccessLog.record(principal, "UPDATE", "inquiry.messages", inquiryId);
        return new SentMessage(MessageItem.of(message, List.of()), true);
    }

    // AI 응대 재개 (B-16) - 안전 신호가 있거나 필수 항목(증상)이 사장님 판단을 기다리면 422 AI_RESUME_BLOCKED
    // 사장님 확인·서비스 불가 단계였으면 정보 수집부터 다시. 직접 상담 요청은 끝냄 (전화 회신 요청은 남김)
    // AI에게 바로 맡김 - 답할 고객 메시지가 없거나 사장님이 이미 답했으면 AI는 다음 고객 메시지부터 답함
    @Transactional
    public HandlerChangeResponse resumeAi(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.lockOwned(principal, inquiryId);
        inquiryAccess.checkOpen(inquiry);
        boolean waitingOwner = inquiry.getStatus() == InquiryStatus.OWNER_CHECK
                || inquiry.getStatus() == InquiryStatus.UNSERVICEABLE;
        if (inquiry.getHandlerMode() == HandlerMode.AI && !waitingOwner) {
            return HandlerChangeResponse.from(inquiry);
        }
        List<String> reasons = resumeBlockers(inquiry);
        if (!reasons.isEmpty()) {
            throw new BusinessException(InquiryErrorCode.AI_RESUME_BLOCKED, Map.of("reasons", reasons));
        }

        int versionBefore = inquiry.getVersion();
        inquiry.resumeAi();
        activeHandoff(inquiry)
                .filter(handoff -> handoff.getRequestType() == HandoffRequestType.DIRECT_CHAT)
                .ifPresent(handoff -> handoff.changeStatus(HandoffStatus.DONE, clock.instant()));
        inquiryAccess.markInfoChanged(inquiry, versionBefore);
        if (inquiry.isAiAnswering()) {
            eventPublisher.publishEvent(new AiAnalysisRequested(inquiry.getId()));
        }
        AccessLog.record(principal, "UPDATE", "inquiry.handler", inquiryId);
        return HandlerChangeResponse.from(inquiry);
    }

    // 문의 종료 (B-16) - 예약은 취소하지 않음, 진행 중 상담·회신 요청은 끝냄. 이미 종료됐으면 그대로 200
    // 고객 상담 토큰은 모두 철회 (명세 1.5) - 종료 뒤 고객 기기에서 방문정보·대화를 더 읽지 못함 (401)
    // 다시 종료해도 철회는 다시 함 (이전에 남은 토큰 정리)
    @Transactional
    public CloseInquiryResponse close(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.lockOwned(principal, inquiryId);
        if (inquiry.getStatus() != InquiryStatus.CLOSED) {
            int versionBefore = inquiry.getVersion();
            inquiry.changeStatus(InquiryStatus.CLOSED);
            activeHandoff(inquiry).ifPresent(handoff -> handoff.changeStatus(HandoffStatus.DONE, clock.instant()));
            inquiryAccess.bumpVersion(inquiry, versionBefore);
            AccessLog.record(principal, "UPDATE", "inquiry.status", inquiryId);
        }
        authTokenService.revokeInquiryTokens(inquiry.getId());
        Long reservationId = reservationRepository
                .findByInquiryIdAndStatusNot(inquiry.getId(), ReservationStatus.CANCELED)
                .map(Reservation::getId)
                .orElse(null);
        return CloseInquiryResponse.of(inquiry, reservationId);
    }

    // 상담·회신 요청 처리 상태 (B-15·C-18) - WAITING → IN_PROGRESS → DONE, 같은 상태면 그대로 200
    // 끝낸 요청은 되돌리지 않음 (409) - 고객이 새로 요청
    @Transactional
    public OwnerHandoffResponse updateHandoff(AuthPrincipal principal, Long handoffId,
                                              UpdateHandoffRequest request) {
        if (request.status() == HandoffStatus.WAITING) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("status", "IN_PROGRESS나 DONE으로 보내 주세요")));
        }
        // 문의를 먼저 잠근 뒤 요청을 읽음 - 고객의 전화 회신 전환과 겹쳐도 차례로 처리
        Inquiry inquiry = handoffRepository.findInquiryId(handoffId)
                .flatMap(inquiryId -> inquiryAccess.tryLockOwned(principal, inquiryId))
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.HANDOFF_NOT_FOUND));
        HandoffRequest handoff = handoffRepository.findById(handoffId).orElseThrow();
        if (handoff.getStatus() == request.status()) {
            // 회신 번호를 돌려주므로 조회로 기록
            AccessLog.record(principal, "VIEW", "handoff_request", handoffId);
            return OwnerHandoffResponse.from(handoff);
        }
        if (handoff.getStatus() == HandoffStatus.DONE) {
            throw new BusinessException(InquiryErrorCode.HANDOFF_ALREADY_DONE,
                    Collections.singletonMap("handoffId", handoffId));
        }
        int versionBefore = inquiry.getVersion();
        handoff.changeStatus(request.status(), clock.instant());
        // 문의 화면이 요청 상태가 바뀐 것을 알게 version을 올림
        inquiryAccess.bumpVersion(inquiry, versionBefore);
        AccessLog.record(principal, "UPDATE", "handoff_request", handoffId);
        return OwnerHandoffResponse.from(handoff);
    }

    private List<String> resumeBlockers(Inquiry inquiry) {
        List<String> reasons = new ArrayList<>();
        if (inquiry.isSafetyFlag()) {
            reasons.add("SAFETY");
        }
        if (!fieldService.findRequiredWaitingOwner(List.of(inquiry.getId())).isEmpty()) {
            reasons.add("REQUIRED_FIELD");
        }
        return reasons;
    }

    private Optional<HandoffRequest> activeHandoff(Inquiry inquiry) {
        return handoffRepository.findFirstByInquiryIdAndStatusIn(inquiry.getId(), HandoffRequest.ACTIVE);
    }
}
