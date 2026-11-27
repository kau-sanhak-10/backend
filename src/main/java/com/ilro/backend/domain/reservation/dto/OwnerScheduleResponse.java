package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.owner.dto.OwnerBlockResponse;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.reservation.entity.RequestedBy;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.service.VisitTimeline.TravelStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

// 사장님 일정 (B-17·B-28) - 날짜별 영업시간·차단 시간·방문, 방문마다 이동/여유/작업시간
// 이동시간은 지금 앞뒤 방문으로 다시 셈 (지도 장애면 travelStatus UNKNOWN - 화면 전체를 막지 않음)
// needsCheck: 이동이 빠듯하거나(CONFLICT) 이동시간을 몰라(UNKNOWN) 사장님이 확인할 방문이 있음
public record OwnerScheduleResponse(LocalDate from, LocalDate to, List<Day> days) {

    // closed면 openTime·closeTime이 null, visitCount는 그날 시작하는 방문 수 (하루 한도와 비교)
    public record Day(LocalDate date, boolean closed, LocalTime openTime, LocalTime closeTime, LocalTime lunchStart,
                      LocalTime lunchEnd, Integer maxDailyVisits, int visitCount, boolean needsCheck,
                      List<OwnerBlockResponse> blocks, List<Visit> visits) {
    }

    // previousReservationId·travelMinutes·bufferMinutes·slackMinutes·travelStatus는 VisitTimeline.PlannedVisit 그대로
    // activeChange: 진행 중 변경 요청·제안 (없으면 null)
    public record Visit(Long reservationId, ReservationStatus status, OffsetDateTime startAt, OffsetDateTime endAt,
                        int workMinutes, boolean isManual, WorkCategory workCategory, Long inquiryId,
                        String customerName, String customerPhone, String address, Long previousReservationId,
                        Integer travelMinutes, Integer bufferMinutes, Integer slackMinutes, TravelStatus travelStatus,
                        ActiveChange activeChange) {
    }

    public record ActiveChange(Long changeId, ReservationChangeStatus status, RequestedBy requestedBy,
                               OffsetDateTime expiresAt) {
    }
}
