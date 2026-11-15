package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.AuthTokenService.IssuedToken;
import com.ilro.backend.domain.inquiry.dto.CustomerInquiriesResponse;
import com.ilro.backend.domain.inquiry.dto.InquiryAccessTokenResponse;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 기존 상담 이어가기 (C-03, 명세 4.1, BE-18)
// 상담 복구 고객 토큰(cus_, INQUIRY_ACCESS)으로 그 번호·그 업체의 문의만 보고, 고른 문의의 새 문의 토큰을 받음
// 같은 번호라는 이유로 문의를 합치지 않음 - 문의마다 따로 이어감
@Service
@RequiredArgsConstructor
public class InquiryRecoveryService {

    // 한 업체에 한 번호로 남긴 문의는 많지 않음 - 최근 것만
    static final int MAX_INQUIRIES = 50;

    private final InquiryRepository inquiryRepository;
    private final InquiryAccess inquiryAccess;
    private final AuthTokenService authTokenService;

    @Transactional(readOnly = true)
    public CustomerInquiriesResponse list(AuthPrincipal principal) {
        checkRecoveryScope(principal);
        List<Inquiry> inquiries = inquiryRepository.findByCustomerPhone(principal.ownerId(),
                principal.customerPhone(), PageRequest.of(0, MAX_INQUIRIES));
        inquiries.forEach(inquiry -> AccessLog.record(principal, "VIEW", "inquiry", inquiry.getId()));
        return CustomerInquiriesResponse.from(inquiries);
    }

    // 다른 번호·다른 업체·삭제된 문의는 있다는 사실도 숨기고 404, 종료된 상담은 409
    @Transactional
    public InquiryAccessTokenResponse issueAccessToken(AuthPrincipal principal, Long inquiryId) {
        checkRecoveryScope(principal);
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .filter(found -> !found.isDeleted() && found.getOwner().getId().equals(principal.ownerId())
                        && found.getCustomer() != null
                        && found.getCustomer().getPhone().equals(principal.customerPhone()))
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        inquiryAccess.checkOpen(inquiry);
        IssuedToken token = authTokenService.issueInquiryToken(inquiry);
        AccessLog.record(principal, "VIEW", "inquiry", inquiryId);
        return new InquiryAccessTokenResponse(inquiry.getId(), token.token(), KstTime.of(token.expiresAt()));
    }

    // 문의 토큰 → 403, 예약 조회 고객 토큰 → 403 VERIFICATION_PURPOSE_MISMATCH (상담 복구 인증을 다시 받아야 함)
    private static void checkRecoveryScope(AuthPrincipal principal) {
        if (principal.type() != AuthTokenType.CUSTOMER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (principal.scope() != AuthTokenScope.INQUIRY_ACCESS) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_PURPOSE_MISMATCH);
        }
    }
}
