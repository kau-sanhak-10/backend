package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.global.common.util.InputRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

// 사장님 문의 수정 (B-14) - 방문정보·접수 항목 중 보낸 것만 바뀜 (둘 중 하나는 보냄)
// 항목은 출처 OWNER·확인됨으로 새 값이 되고 이전 값은 이력으로 남음. AI·고객이 덮어쓰지 않음
public record OwnerUpdateInquiryRequest(
        // 화면이 보고 있던 문의 version (명세 1.7)
        @NotNull(message = "필수 값입니다")
        Integer version,

        @Valid
        OwnerVisitInfo visitInfo,

        @Size(min = 1, max = 10, message = "1~10개 항목을 보내 주세요")
        List<@Valid @NotNull(message = "필수 값입니다") OwnerFieldChange> fields) {

    // 방문정보 전체 교체 - 비울 칸은 null (고객 방문정보 C-06과 같은 규칙)
    // 확정·요청 예약이 있으면 주소·연락처·작업은 예약 변경 절차로 (422 RESERVATION_CHANGE_REQUIRED)
    public record OwnerVisitInfo(
            @NotBlank(message = "필수 값입니다") @Size(max = 50, message = "50자 이하여야 합니다")
            String name,

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

            @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE)
            @Size(max = 255, message = "255자 이하여야 합니다")
            String accessNote,

            // 선택 - 업체가 제공하는 작업만, null이면 지금 값을 그대로 둠
            WorkCategory workCategory) {
    }

    // 사장님이 확인한 값 - manufacturer·model·boiler_type·error_code·symptom
    public record OwnerFieldChange(
            @NotNull(message = "필수 값입니다")
            String fieldKey,

            @NotBlank(message = "필수 값입니다") @Size(max = 255, message = "255자 이하여야 합니다")
            String value) {
    }
}
