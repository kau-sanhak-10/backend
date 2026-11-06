package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.RequiredConsent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.global.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

// 지금 동의받는 약관 버전 (app.intake.terms-version) - 약관이 바뀌면 설정만 올림
@Component
public class IntakeTerms {

    private final String version;

    public IntakeTerms(@Value("${app.intake.terms-version}") String version) {
        if (version == null || version.isBlank() || version.length() > 20) {
            throw new IllegalStateException("app.intake.terms-version은 1~20자여야 합니다");
        }
        this.version = version;
    }

    public String version() {
        return version;
    }

    // 403 CONSENT_REQUIRED + 지금 버전으로 받아야 할 동의
    public BusinessException consentRequired(Collection<ConsentType> types) {
        List<RequiredConsent> required = types.stream().map(type -> new RequiredConsent(type, version)).toList();
        return new BusinessException(InquiryErrorCode.CONSENT_REQUIRED,
                Collections.singletonMap("requiredConsents", required));
    }
}
