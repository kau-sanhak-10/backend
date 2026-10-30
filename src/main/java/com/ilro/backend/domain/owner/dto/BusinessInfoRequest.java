package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// 업체 정보 저장 (B-05) - 전체 교체, 비울 칸은 null
// 출발지는 주소·좌표를 함께 보내거나 셋 다 null (좌표 변환은 지도 연동 후 서버에서 하도록 바꿀 수 있음)
public record BusinessInfoRequest(
        @NotBlank(message = "필수 값입니다") @Size(max = 100, message = "100자 이하여야 합니다")
        String businessName,

        // 고객에게 보여줄 대표 연락처, null이면 로그인 번호를 보여 줌
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        String contactPhone,

        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String address,

        @DecimalMin(value = "-90", message = "-90 ~ 90 사이여야 합니다")
        @DecimalMax(value = "90", message = "-90 ~ 90 사이여야 합니다")
        BigDecimal lat,

        @DecimalMin(value = "-180", message = "-180 ~ 180 사이여야 합니다")
        @DecimalMax(value = "180", message = "-180 ~ 180 사이여야 합니다")
        BigDecimal lng,

        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 500, message = "500자 이하여야 합니다")
        String customerNotice,

        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String serviceDescription) {
}
