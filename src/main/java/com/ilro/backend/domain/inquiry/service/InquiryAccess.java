package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.ConsentRepository;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

// 고객 문의 API 공통 확인 (명세 1.5·1.7)
// 문의 토큰은 발급받은 그 문의에만 쓰임 - 다른 문의·삭제된 문의는 있다는 사실도 숨기고 404
// 고객 토큰(cus_)은 문의를 고른 뒤 새로 받은 문의 토큰으로 써야 하므로(BE-18) 여기서는 403
@Component
@RequiredArgsConstructor
public class InquiryAccess {

    private final InquiryRepository inquiryRepository;
    private final ConsentRepository consentRepository;
    private final IntakeTerms terms;
    private final EntityManager entityManager;

    public Inquiry find(AuthPrincipal principal, Long inquiryId) {
        checkScope(principal, inquiryId);
        return inquiryRepository.findById(inquiryId)
                .filter(inquiry -> !inquiry.isDeleted())
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
    }

    // 쓰기는 문의 행을 잠그고 차례로 처리 (3초 넘게 기다리면 503)
    public Inquiry lock(AuthPrincipal principal, Long inquiryId) {
        checkScope(principal, inquiryId);
        return inquiryRepository.findByIdForUpdate(inquiryId)
                .filter(inquiry -> !inquiry.isDeleted())
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
    }

    // 종료된 상담 409, 상담을 받을 수 없게 된 업체 403, 필수 동의 없음 403
    public void checkWritable(Inquiry inquiry) {
        if (inquiry.getStatus() == InquiryStatus.CLOSED) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_CLOSED);
        }
        if (!inquiry.getOwner().isServingCustomers()) {
            throw new BusinessException(InquiryErrorCode.OWNER_UNAVAILABLE);
        }
        requireConsent(inquiry, ConsentType.PRIVACY_REQUIRED);
    }

    public void requireConsent(Inquiry inquiry, ConsentType type) {
        if (!consentRepository.existsByInquiryIdAndConsentType(inquiry.getId(), type)) {
            throw terms.consentRequired(List.of(type));
        }
    }

    // 화면이 보고 있던 버전과 다르면 덮어쓰지 않음 (명세 1.7) - 잠근 뒤에 비교
    public void checkVersion(Inquiry inquiry, int version) {
        if (inquiry.getVersion() != version) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_VERSION_CONFLICT,
                    Collections.singletonMap("currentVersion", inquiry.getVersion()));
        }
    }

    // 접수정보·방문정보를 바꾼 뒤 호출
    // version은 정확히 1 올림 - 고객 이름·항목 상태처럼 문의 칸 밖만 바뀌어도 다른 화면이 충돌을 알게
    // 응답에 새 version을 쓰도록 여기서 반영하고, AI 입력 순번도 올림 (명세 6.1)
    public void markInfoChanged(Inquiry inquiry, int versionBefore) {
        bumpVersion(inquiry, versionBefore);
        inquiryRepository.incrementAiInputSeq(inquiry.getId());
    }

    // version을 정확히 1 올림 - 문의 칸이 바뀌었으면 저장하면서 이미 올랐고, 아니면 여기서 올림
    // AI 결과 적용도 이것만 씀 (AI 입력 순번은 올리지 않음, 명세 6.1)
    public void bumpVersion(Inquiry inquiry, int versionBefore) {
        entityManager.flush();
        if (Objects.equals(inquiry.getVersion(), versionBefore)) {
            entityManager.lock(inquiry, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
    }

    private static void checkScope(AuthPrincipal principal, Long inquiryId) {
        if (principal.type() != AuthTokenType.INQUIRY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (!principal.inquiryId().equals(inquiryId)) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND);
        }
    }
}
