package com.ilro.backend.domain.inquiry.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

// 접수 항목 키 (inquiry_field.field_key, 명세 2.2) - 고객·사장님이 고칠 수 있는 항목, AI 규칙과 함께 늘림
// maxLength는 같은 값을 담는 inquiry 칸 길이
@Getter
@RequiredArgsConstructor
public enum InquiryFieldKey {
    MANUFACTURER("manufacturer", 50),
    MODEL("model", 100),
    BOILER_TYPE("boiler_type", 50),
    ERROR_CODE("error_code", 20),
    SYMPTOM("symptom", 255);

    private final String key;
    private final int maxLength;

    public static Optional<InquiryFieldKey> fromKey(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }
}
