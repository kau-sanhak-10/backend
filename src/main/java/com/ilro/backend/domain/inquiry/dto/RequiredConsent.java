package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.ConsentType;

// CONSENT_REQUIRED의 result.requiredConsents 항목 - 이 버전 약관에 동의해야 함
public record RequiredConsent(ConsentType type, String termsVersion) {
}
