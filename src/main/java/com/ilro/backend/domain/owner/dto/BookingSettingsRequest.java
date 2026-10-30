package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.TravelMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

// 예약 기준 저장 (B-07·B-08, 명세 4.4) - 전체 교체, 요일 7개 모두 보냄
// 점심은 둘 다 null(차단 없음)이거나 시작 < 끝, maxDailyVisits null = 제한 없음, travelMode는 CAR만 (null이면 CAR)
public record BookingSettingsRequest(
        @NotNull(message = "필수 값입니다") @Size(min = 7, max = 7, message = "월~일 7개 요일을 모두 보내 주세요")
        List<@Valid @NotNull(message = "필수 값입니다") BusinessHour> businessHours,
        LocalTime lunchStart,
        LocalTime lunchEnd,
        @Positive(message = "1 이상이어야 합니다") Integer maxDailyVisits,
        @NotNull(message = "필수 값입니다") @PositiveOrZero(message = "0 이상이어야 합니다")
        @Max(value = 1440, message = "1440분 이하여야 합니다")
        Integer bufferMinutes,
        TravelMode travelMode) {

    // dayOfWeek 1=월 ~ 7=일, 쉬는 날은 openTime·closeTime null
    public record BusinessHour(
            @NotNull(message = "필수 값입니다") @Min(value = 1, message = "1~7이어야 합니다")
            @Max(value = 7, message = "1~7이어야 합니다")
            Integer dayOfWeek,
            LocalTime openTime,
            LocalTime closeTime,
            @NotNull(message = "필수 값입니다") Boolean isClosed) {
    }
}
