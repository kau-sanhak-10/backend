package com.ilro.backend.domain.owner.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 업체 설정·운영 오류 코드 (명세 3.3·3.7) - 업체 설정(BE-03)과 DB 제약 대응(3.10), 운영 관련은 BE-17에서 추가
@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {

    // result: daysOfWeek
    LUNCH_OUTSIDE_BUSINESS_HOURS(HttpStatus.UNPROCESSABLE_CONTENT, "LUNCH_OUTSIDE_BUSINESS_HOURS", "점심시간이 영업시간 밖에 있습니다"),
    // result: missing (BUSINESS_HOURS·WORK_TYPE·SERVICE_AREA·START_ADDRESS)
    AUTO_BOOKING_SETUP_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "AUTO_BOOKING_SETUP_INCOMPLETE", "자동 예약에 필요한 설정이 빠져 있습니다"),

    OWNER_PHONE_DUPLICATED(HttpStatus.CONFLICT, "OWNER_PHONE_DUPLICATED", "같은 로그인 번호로 등록된 업체가 있습니다"),
    WORK_TYPE_DUPLICATED(HttpStatus.CONFLICT, "WORK_TYPE_DUPLICATED", "이미 등록된 작업 종류입니다"),
    SERVICE_AREA_DUPLICATED(HttpStatus.CONFLICT, "SERVICE_AREA_DUPLICATED", "이미 등록된 서비스 지역입니다"),
    // 승인 전에 운영 상태 변경·접수 링크 발급 (명세 3.7)
    OWNER_NOT_APPROVED(HttpStatus.CONFLICT, "OWNER_NOT_APPROVED", "승인된 업체에만 할 수 있는 작업입니다"),
    // result: approvalStatus - 이미 승인·거절한 신청을 다시 심사 (BE-17)
    OWNER_ALREADY_REVIEWED(HttpStatus.CONFLICT, "OWNER_ALREADY_REVIEWED", "이미 심사를 마친 신청입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
