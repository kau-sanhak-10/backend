package com.ilro.backend.domain.owner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerBusinessHour;
import com.ilro.backend.domain.owner.entity.TravelMode;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 예약 기준 (B-07·B-08) - 아직 저장하지 않은 요일은 쉬는 날로 보여 줌, 시각은 "09:00" (명세 1.2)
public record BookingSettingsResponse(List<BusinessHour> businessHours,
                                      @JsonFormat(pattern = "HH:mm") LocalTime lunchStart,
                                      @JsonFormat(pattern = "HH:mm") LocalTime lunchEnd,
                                      Integer maxDailyVisits, int bufferMinutes, TravelMode travelMode,
                                      boolean autoBookingEnabled) {

    public record BusinessHour(int dayOfWeek,
                               @JsonFormat(pattern = "HH:mm") LocalTime openTime,
                               @JsonFormat(pattern = "HH:mm") LocalTime closeTime,
                               boolean isClosed) {
    }

    public static BookingSettingsResponse of(Owner owner, List<OwnerBusinessHour> hours) {
        Map<DayOfWeek, OwnerBusinessHour> byDay = hours.stream()
                .collect(Collectors.toMap(OwnerBusinessHour::getDayOfWeek, Function.identity()));
        List<BusinessHour> businessHours = Arrays.stream(DayOfWeek.values())
                .map(day -> {
                    OwnerBusinessHour hour = byDay.get(day);
                    return hour == null
                            ? new BusinessHour(day.getValue(), null, null, true)
                            : new BusinessHour(day.getValue(), hour.getOpenTime(), hour.getCloseTime(), hour.isClosed());
                })
                .toList();
        return new BookingSettingsResponse(businessHours, owner.getLunchStart(), owner.getLunchEnd(),
                owner.getMaxDailyVisits(), owner.getBufferMinutes(), owner.getTravelMode(),
                owner.isAutoBookingEnabled());
    }
}
