package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.exception.SlotUnavailableReason;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 방문 가능 시간 후보 (C-12, 명세 5.2) - workMinutes는 지금 설정된 그 작업의 소요시간 (이동·여유시간은 따로)
public record AvailableSlotsResponse(int workMinutes, List<Day> days) {

    // 가능한 시각이 하나도 없으면 emptyReason에 가장 많이 걸린 이유 (SLOT_UNAVAILABLE의 reason과 같은 값)
    public record Day(LocalDate date, List<Slot> slots, SlotUnavailableReason emptyReason) {
    }

    public record Slot(OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
