package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.CallbackRequest;
import com.ilro.backend.domain.inquiry.dto.HandoffResponse;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.HandoffRequestType;
import com.ilro.backend.domain.inquiry.entity.HandoffStatus;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.repository.HandoffRequestRepository;
import com.ilro.backend.domain.notification.service.NeedsCheckNotifier;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 고객의 직접 상담·전화 회신 요청 (C-10·C-18, 명세 4.2·5.5)
// 진행 중 요청은 문의당 1건 - 문의 행을 잠그고 확인한 뒤 저장 (uq_handoff_request_active)
// 요청이 오면 AI 답변을 멈추고 사장님 확인으로 넘긴 뒤 알림 - 기존 대화·사진은 그대로 사장님 상세에서 봄
@Service
@RequiredArgsConstructor
public class CustomerHandoffService {

    private final InquiryAccess inquiryAccess;
    private final HandoffRequestRepository handoffRepository;
    private final NeedsCheckNotifier needsCheckNotifier;

    // created: 새 요청이면 true(201), 진행 중 요청을 돌려주거나 바꿨으면 false(200)
    public record Requested(HandoffResponse handoff, boolean created) {
    }

    // 직접 상담 요청 (C-10) - 진행 중 요청이 있으면 그 요청을 200으로 (AI가 답하는 중이면 다시 사장님 확인으로)
    @Transactional
    public Requested requestDirectChat(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        Optional<HandoffRequest> active = findActive(inquiry);
        if (active.isPresent()) {
            askOwnerAgain(principal, inquiry, active.get());
            return new Requested(HandoffResponse.from(active.get()), false);
        }
        HandoffRequest handoff = handoffRepository.save(HandoffRequest.builder()
                .inquiry(inquiry)
                .requestType(HandoffRequestType.DIRECT_CHAT)
                .build());
        askOwner(inquiry, handoff);
        AccessLog.record(principal, "UPDATE", "handoff_request", handoff.getId());
        return new Requested(HandoffResponse.from(handoff), true);
    }

    // 전화 회신 요청 (C-18, 명세 5.5) - 이름·번호는 회신 요청에만 저장
    // 고객 번호·예약 연락처·문자 인증은 바꾸지 않고 어떤 인증 권한도 주지 않음
    // 대기 중 직접 상담은 같은 요청을 전화 회신으로 바꿈, 그 밖의 진행 중 요청은 그대로 200
    @Transactional
    public Requested requestCallback(AuthPrincipal principal, Long inquiryId, CallbackRequest request) {
        String phone = VisitInfoWriter.mobilePhone(request.phone(), "phone");
        String name = request.name().strip();

        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        Optional<HandoffRequest> active = findActive(inquiry);
        if (active.isPresent()) {
            HandoffRequest handoff = active.get();
            if (handoff.getRequestType() == HandoffRequestType.DIRECT_CHAT
                    && handoff.getStatus() == HandoffStatus.WAITING) {
                handoff.switchToCallback(name, phone);
                askOwner(inquiry, handoff);
                AccessLog.record(principal, "UPDATE", "handoff_request", handoff.getId());
            } else {
                askOwnerAgain(principal, inquiry, handoff);
            }
            return new Requested(HandoffResponse.from(handoff), false);
        }
        HandoffRequest handoff = handoffRepository.save(HandoffRequest.builder()
                .inquiry(inquiry)
                .requestType(HandoffRequestType.CALLBACK)
                .name(name)
                .phone(phone)
                .build());
        askOwner(inquiry, handoff);
        AccessLog.record(principal, "UPDATE", "handoff_request", handoff.getId());
        return new Requested(HandoffResponse.from(handoff), true);
    }

    private Optional<HandoffRequest> findActive(Inquiry inquiry) {
        return handoffRepository.findFirstByInquiryIdAndStatusIn(inquiry.getId(), HandoffRequest.ACTIVE);
    }

    // 사장님 확인 + 알림 (요청·종류마다 하나) - 다른 화면이 바뀐 것을 알게 version을 올림
    // 단계가 바뀌면 처리 중이던 AI 결과는 적용되지 않음 (AI가 답하는 단계가 아님)
    private void askOwner(Inquiry inquiry, HandoffRequest handoff) {
        askOwner(inquiry, "NEEDS_CHECK:HANDOFF:" + handoff.getId() + ":" + handoff.getRequestType());
    }

    // 사장님이 AI를 다시 켠 뒤에도 전화 회신 요청은 남아 있음 - 그 상태에서 고객이 다시 요청하면 AI를 멈추고 다시 알림
    // 이미 사장님 확인 중이면 아무것도 바꾸지 않음
    private void askOwnerAgain(AuthPrincipal principal, Inquiry inquiry, HandoffRequest handoff) {
        if (!inquiry.canMoveToOwnerCheck()) {
            return;
        }
        askOwner(inquiry, "NEEDS_CHECK:HANDOFF:" + handoff.getId() + ":" + handoff.getRequestType() + ":"
                + inquiry.getVersion());
        AccessLog.record(principal, "UPDATE", "handoff_request", handoff.getId());
    }

    private void askOwner(Inquiry inquiry, String dedupeKey) {
        int versionBefore = inquiry.getVersion();
        inquiry.moveToOwnerCheck();
        needsCheckNotifier.notify(inquiry, dedupeKey);
        inquiryAccess.bumpVersion(inquiry, versionBefore);
    }
}
