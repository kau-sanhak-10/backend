package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import com.ilro.backend.domain.ai.repository.AiDecisionRepository;
import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.customer.repository.CustomerRepository;
import com.ilro.backend.domain.inquiry.dto.InquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.VisitInfoRequest;
import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.repository.ConsentRepository;
import com.ilro.backend.domain.inquiry.repository.HandoffRequestRepository;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 고객 문의 조회·방문정보 저장 (C-03·C-06·C-09, 명세 4.2)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerInquiryService {

    private final InquiryAccess inquiryAccess;
    private final VisitInfoWriter visitInfoWriter;
    private final CustomerRepository customerRepository;
    private final ReservationRepository reservationRepository;
    private final ConsentRepository consentRepository;
    private final AiDecisionRepository aiDecisionRepository;
    private final HandoffRequestRepository handoffRepository;

    public InquiryDetailResponse getInquiry(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.find(principal, inquiryId);
        InquiryDetailResponse response = detail(inquiry);
        AccessLog.record(principal, "VIEW", "inquiry", inquiryId);
        return response;
    }

    @Transactional
    public InquiryDetailResponse updateVisitInfo(AuthPrincipal principal, Long inquiryId, VisitInfoRequest request) {
        String phone = VisitInfoWriter.mobilePhone(request.phone(), "phone");

        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        inquiryAccess.checkVersion(inquiry, request.version());
        int versionBefore = inquiry.getVersion();
        boolean changed = visitInfoWriter.write(inquiry, new VisitInfoWriter.Input(request.name(), phone,
                request.address(), request.addressDetail(), request.lat(), request.lng(), request.accessNote(),
                request.workCategory(), ""), false);
        if (changed) {
            inquiryAccess.markInfoChanged(inquiry, versionBefore);
        }

        InquiryDetailResponse response = detail(inquiry);
        AccessLog.record(principal, changed ? "UPDATE" : "VIEW", "inquiry", inquiryId);
        return response;
    }

    // 고객 이름은 번호를 인증했거나 이 문의만 쓰는 고객일 때만 보여 줌 (다른 사람이 같은 번호로 넣은 이름을 숨김)
    private InquiryDetailResponse detail(Inquiry inquiry) {
        Customer customer = inquiry.getCustomer();
        boolean showName = customer != null && (inquiry.getPhoneVerifiedAt() != null
                || customerRepository.isUsedOnlyBy(customer.getId(), inquiry.getId()));
        Reservation reservation = reservationRepository
                .findByInquiryIdAndStatusNot(inquiry.getId(), ReservationStatus.CANCELED)
                .orElse(null);
        AiAction nextAction = inquiry.getHandlerMode() != HandlerMode.AI ? null : aiDecisionRepository
                .findFirstByInquiryIdAndStageAndStatusOrderByIdDesc(inquiry.getId(), AiStage.DECIDE,
                        AiDecisionStatus.SUCCESS)
                .map(AiDecision::getAction)
                .orElse(null);
        List<ConsentType> consents = consentRepository.findByInquiryIdOrderByIdAsc(inquiry.getId()).stream()
                .map(Consent::getConsentType)
                .distinct()
                .toList();
        HandoffRequest handoff = handoffRepository
                .findFirstByInquiryIdAndStatusIn(inquiry.getId(), HandoffRequest.ACTIVE)
                .orElse(null);
        return InquiryDetailResponse.of(inquiry, showName, nextAction, consents, reservation, handoff);
    }
}
