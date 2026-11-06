package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// 방문정보 저장 (C-06) - 전체 교체, 비울 칸은 null
// 주소는 주소 검색 결과의 좌표(lat·lng)와 함께 보냄 (지도 API에는 address만, 상세 주소는 쓰지 않음)
public record VisitInfoRequest(
        // 화면이 보고 있던 문의 version (명세 1.7)
        @NotNull(message = "필수 값입니다")
        Integer version,

        @NotBlank(message = "필수 값입니다") @Size(max = 50, message = "50자 이하여야 합니다")
        String name,

        // 휴대전화 번호 - 예약 확정 전에 이 번호로 문자 인증 (C-13)
        @NotBlank(message = "필수 값입니다")
        String phone,

        @NotBlank(message = "필수 값입니다") @Size(max = 255, message = "255자 이하여야 합니다")
        String address,

        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String addressDetail,

        @NotNull(message = "필수 값입니다")
        @DecimalMin(value = "-90", message = "-90 ~ 90 사이여야 합니다")
        @DecimalMax(value = "90", message = "-90 ~ 90 사이여야 합니다")
        BigDecimal lat,

        @NotNull(message = "필수 값입니다")
        @DecimalMin(value = "-180", message = "-180 ~ 180 사이여야 합니다")
        @DecimalMax(value = "180", message = "-180 ~ 180 사이여야 합니다")
        BigDecimal lng,

        // 주차·출입 안내
        @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
        @Size(max = 255, message = "255자 이하여야 합니다")
        String accessNote,

        // 선택 - 업체가 제공하는 작업만, null이면 지금 값(AI 분류 포함)을 그대로 둠
        WorkCategory workCategory) {
}
