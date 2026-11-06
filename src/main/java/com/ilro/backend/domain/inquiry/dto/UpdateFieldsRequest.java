package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

// 접수정보 고객 수정 (C-08) - 보낸 항목만 바뀜, 출처는 서버가 CUSTOMER로 붙임
public record UpdateFieldsRequest(
        // 화면이 보고 있던 문의 version (명세 1.7)
        @NotNull(message = "필수 값입니다")
        Integer version,

        @NotNull(message = "필수 값입니다") @Size(min = 1, max = 10, message = "1~10개 항목을 보내 주세요")
        List<@Valid @NotNull(message = "필수 값입니다") FieldChange> fields) {

    public record FieldChange(
            // manufacturer·model·boiler_type·error_code·symptom
            @NotNull(message = "필수 값입니다")
            String fieldKey,

            @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
            @Size(max = 255, message = "255자 이하여야 합니다")
            String value,

            // 고객이 잘 모름 - 값 없이 보내거나 짐작한 값과 함께 보냄 (미확인으로 저장)
            Boolean unsure) {
    }
}
