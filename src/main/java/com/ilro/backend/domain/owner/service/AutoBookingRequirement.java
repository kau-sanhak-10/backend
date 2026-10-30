package com.ilro.backend.domain.owner.service;

// 자동 예약을 켜기 전에 있어야 하는 설정 (명세 3.3 AUTO_BOOKING_SETUP_INCOMPLETE의 missing)
public enum AutoBookingRequirement {
    BUSINESS_HOURS, // 문을 여는 요일이 하루 이상
    WORK_TYPE, // 제공하면서 자동 예약도 허용한 작업이 하나 이상
    SERVICE_AREA, // 서비스 지역 하나 이상
    START_ADDRESS // 출발지 주소·좌표
}
