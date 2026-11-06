package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.AuthTokenService.IssuedToken;
import com.ilro.backend.domain.inquiry.dto.IntakeInfoResponse;
import com.ilro.backend.domain.inquiry.dto.StartInquiryRequest;
import com.ilro.backend.domain.inquiry.dto.StartInquiryResponse;
import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.ConsentRepository;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 고객 접수 링크로 업체 확인·상담 시작 (C-01·C-06, 명세 4.2)
// 필수 동의를 먼저 기록해야 문의가 생기므로, 동의 전에는 대화·방문정보를 저장할 곳이 없음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntakeService {

    private static final List<IntakeInfoResponse.ConsentItem> CONSENTS = List.of(
            new IntakeInfoResponse.ConsentItem(ConsentType.PRIVACY_REQUIRED, true),
            new IntakeInfoResponse.ConsentItem(ConsentType.PHOTO, false));

    private final OwnerRepository ownerRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final InquiryRepository inquiryRepository;
    private final ConsentRepository consentRepository;
    private final AuthTokenService authTokenService;
    private final IntakeTerms terms;

    // 접수 중지여도 업체 안내는 보여 줌 (예약 조회 안내용)
    public IntakeInfoResponse getIntake(String linkToken) {
        Owner owner = findServingOwner(linkToken);
        List<WorkCategory> workCategories = workTypeRepository.findByOwnerId(owner.getId()).stream()
                .filter(OwnerWorkType::isActive)
                .map(OwnerWorkType::getWorkCategory)
                .sorted()
                .toList();
        String contactPhone = owner.getContactPhone() != null ? owner.getContactPhone() : owner.getPhone();
        return new IntakeInfoResponse(owner.getBusinessName(), contactPhone, owner.getCustomerNotice(),
                owner.getServiceDescription(), workCategories, !owner.isIntakePaused(), terms.version(), CONSENTS);
    }

    // 동의 기록 + 문의 생성 + 문의 토큰 발급을 한 번에
    @Transactional
    public StartInquiryResponse startInquiry(String linkToken, StartInquiryRequest request) {
        Owner owner = findServingOwner(linkToken);
        if (owner.isIntakePaused()) {
            throw new BusinessException(InquiryErrorCode.INTAKE_PAUSED,
                    Collections.singletonMap("reservationLookupAvailable", true));
        }
        Set<ConsentType> consentTypes = checkConsents(request.consents());

        Inquiry inquiry = inquiryRepository.save(Inquiry.builder().owner(owner).build());
        for (ConsentType type : consentTypes) {
            consentRepository.save(Consent.builder()
                    .inquiry(inquiry)
                    .consentType(type)
                    .termsVersion(terms.version())
                    .build());
        }
        IssuedToken token = authTokenService.issueInquiryToken(inquiry);
        return new StartInquiryResponse(inquiry.getId(), token.token(), KstTime.of(token.expiresAt()));
    }

    // 종류별 한 번씩, 지금 약관 버전이어야 함. 필수 동의가 없거나 이전 버전이면 403 CONSENT_REQUIRED
    private Set<ConsentType> checkConsents(List<StartInquiryRequest.ConsentItem> consents) {
        Set<ConsentType> types = EnumSet.noneOf(ConsentType.class);
        List<ConsentType> outdated = new ArrayList<>();
        for (StartInquiryRequest.ConsentItem consent : consents) {
            if (!types.add(consent.type())) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                        List.of(new FieldErrorResponse("consents", "동의는 종류별로 한 번씩만 보내 주세요")));
            }
            if (!terms.version().equals(consent.termsVersion())) {
                outdated.add(consent.type());
            }
        }
        if (!types.contains(ConsentType.PRIVACY_REQUIRED)) {
            outdated.addFirst(ConsentType.PRIVACY_REQUIRED);
        }
        if (!outdated.isEmpty()) {
            throw terms.consentRequired(outdated);
        }
        return types;
    }

    // 없거나 승인 전·탈퇴 → 404, 설정 중·이용 제한 → 403
    private Owner findServingOwner(String linkToken) {
        Owner owner = ownerRepository.findByLinkToken(linkToken)
                .filter(Owner::isIntakeLinkValid)
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INTAKE_LINK_INVALID));
        if (!owner.isServingCustomers()) {
            throw new BusinessException(InquiryErrorCode.OWNER_UNAVAILABLE);
        }
        return owner;
    }
}
