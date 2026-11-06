package com.ilro.backend.domain.inquiry.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

// 접수 항목 키 (inquiry_field.field_key, 명세 2.2) - 고객·사장님이 고칠 수 있는 항목, AI 규칙과 함께 늘림
// maxLength는 같은 값을 담는 inquiry 칸 길이
// required: 필수 항목 (AI 규칙 rules/inquiry_types.yaml의 공통 필수 symptom과 맞춤)
//   나머지는 참고 항목 - 미확인이어도 '미확인' 표시만 하고 상담·예약을 이어감 (10/26 기준)
@Getter
@RequiredArgsConstructor
public enum InquiryFieldKey {
    MANUFACTURER("manufacturer", 50, false),
    MODEL("model", 100, false),
    BOILER_TYPE("boiler_type", 50, false),
    ERROR_CODE("error_code", 20, false),
    SYMPTOM("symptom", 255, true);

    private final String key;
    private final int maxLength;
    private final boolean required;

    public static List<String> requiredKeys() {
        return Arrays.stream(values()).filter(InquiryFieldKey::isRequired).map(InquiryFieldKey::getKey).toList();
    }

    public static Optional<InquiryFieldKey> fromKey(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }
}
