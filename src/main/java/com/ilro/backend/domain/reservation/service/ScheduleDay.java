package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.map.GeoPoint;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// 업체의 하루 일정 (한국 날짜) - 시간 검사(명세 5.2 3번)에 필요한 값을 한 번 읽어 그날 후보 전체에 씀
// open·close가 null이면 휴무 (영업시간을 안 정한 요일 포함), 점심은 둘 다 있을 때만
// origin: 업체 출발지 좌표 (없으면 null) - 그날 첫 방문은 출발지에서 영업 시작 시각에 출발한다고 봄
// visits: 그날과 겹치는 취소 안 된 예약 (수동·자동·완료 포함), blocks: 그날과 겹치는 차단 시간
public record ScheduleDay(LocalDate date, LocalTime open, LocalTime close, LocalTime lunchStart,
                          LocalTime lunchEnd, Integer maxDailyVisits, int bufferMinutes, GeoPoint origin,
                          List<TimeRange> blocks, List<Visit> visits) {

    // [startAt, endAt) - 끝 시각에 바로 시작하는 것은 겹침이 아님
    public record TimeRange(Instant startAt, Instant endAt) {

        public boolean overlaps(Instant start, Instant end) {
            return startAt.isBefore(end) && start.isBefore(endAt);
        }
    }

    // location: 예약 주소 좌표 (없으면 null - 앞뒤 이동시간을 셀 수 없음)
    public record Visit(Long reservationId, Instant startAt, Instant endAt, GeoPoint location) {

        public boolean overlaps(Instant start, Instant end) {
            return startAt.isBefore(end) && start.isBefore(endAt);
        }
    }

    public boolean isClosed() {
        return open == null || close == null;
    }

    public boolean hasLunch() {
        return lunchStart != null && lunchEnd != null;
    }

    public Instant at(LocalTime time) {
        return date.atTime(time).atZone(KstTime.ZONE).toInstant();
    }

    // 하루 방문 한도에 세는 예약 - 그날(한국 날짜) 시작하는 것
    public long visitsStartingToday() {
        return visits.stream()
                .filter(visit -> visit.startAt().atZone(KstTime.ZONE).toLocalDate().equals(date))
                .count();
    }
}
