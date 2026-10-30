package com.ilro.backend.domain.reservation.exception;

// SLOT_UNAVAILABLE의 result.reason (명세 3.5) - 후보 계산·최종 확정·후보 조회의 emptyReason이 같은 값을 씀
public enum SlotUnavailableReason {
    PAST, // 지난 시각
    OUTSIDE_BUSINESS_HOURS, // 영업시간 밖·휴무
    LUNCH_BREAK, // 점심시간과 겹침
    BLOCKED, // 사장님 차단 시간과 겹침
    DAILY_LIMIT_REACHED, // 하루 최대 방문 건수 도달
    OVERLAP, // 다른 확정 예약과 겹침
    TRAVEL_CONFLICT // 앞뒤 방문지와의 이동시간·여유시간 부족
}
