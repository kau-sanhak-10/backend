package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.WorkCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

// 작업 종류별 제공 여부·소요시간·자동 예약 허용 (B-05·B-08) - 요청·응답 같은 모양
// 저장은 전체 교체: 목록에 없는 작업은 제공 안 함으로 바뀜 (행은 지우지 않음)
public record WorkTypeSettings(
        @NotNull(message = "필수 값입니다") @Size(max = 4, message = "작업 종류는 4개까지입니다")
        List<@Valid @NotNull(message = "필수 값입니다") WorkType> workTypes) {

    // durationMinutes·autoBookingAllowed가 null이면 지금 값 유지 (처음이면 60분·허용 안 함)
    public record WorkType(
            @NotNull(message = "필수 값입니다") WorkCategory workCategory,
            @NotNull(message = "필수 값입니다") Boolean isActive,
            @Positive(message = "1분 이상이어야 합니다") @Max(value = 1440, message = "1440분 이하여야 합니다")
            Integer durationMinutes,
            Boolean autoBookingAllowed) {
    }
}
