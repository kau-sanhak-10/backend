package com.ilro.backend.domain.reservation.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 예약·변경 오류 코드 (명세 3.5) - DB 제약·버전 충돌 대응(3.10)과 방문 가능 시간(BE-09)에 쓰는 것, 나머지는 BE-11·BE-12에서 추가
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    // ---------- 자동 예약 불가 (명세 5.2 1·2번) - 문의는 사장님 확인으로 넘어가고 화면은 "사장님이 확인 후 연락드려요" ----------

    // 업체가 자동 예약을 끔·이 작업은 자동 예약을 허용하지 않음·신규 접수 중지
    AUTO_BOOKING_DISABLED(HttpStatus.UNPROCESSABLE_CONTENT, "AUTO_BOOKING_DISABLED",
            "방문 시간은 사장님이 확인한 뒤 연락드려요"),
    // 작업 유형 미확인·업체가 제공하지 않음·비활성
    WORK_TYPE_NOT_CONFIRMED(HttpStatus.UNPROCESSABLE_CONTENT, "WORK_TYPE_NOT_CONFIRMED",
            "어떤 작업인지 확인이 더 필요해요. 사장님이 확인한 뒤 연락드려요"),
    // result: fields [{fieldKey, reason}]
    REQUIRED_INFO_UNCONFIRMED(HttpStatus.UNPROCESSABLE_CONTENT, "REQUIRED_INFO_UNCONFIRMED",
            "예약에 필요한 정보 확인이 더 필요해요. 사장님이 확인한 뒤 연락드려요"),
    SAFETY_CHECK_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "SAFETY_CHECK_REQUIRED",
            "안전 확인이 먼저 필요해요. 사장님이 확인한 뒤 연락드려요"),
    // 서비스 지역 밖·주소 좌표가 없어 판별 불가
    OUT_OF_SERVICE_AREA(HttpStatus.UNPROCESSABLE_CONTENT, "OUT_OF_SERVICE_AREA",
            "방문 지역 확인이 필요해요. 사장님이 확인한 뒤 연락드려요"),
    // 이동시간 계산 불가 - 시간을 임의로 만들지 않음
    TRAVEL_TIME_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "TRAVEL_TIME_UNAVAILABLE",
            "이동 시간을 계산하지 못했어요. 사장님이 확인한 뒤 연락드려요"),
    // 사장님이 직접 응대 중이거나 이미 사장님 확인 단계 - 자동 예약 안 함 (명세에 없음, BE-09에서 추가)
    OWNER_HANDLING(HttpStatus.CONFLICT, "OWNER_HANDLING", "사장님이 확인하고 있는 상담이에요. 사장님이 연락드릴 거예요"),

    // ---------- 시간·예약 ----------

    // result: reason (SlotUnavailableReason)
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "선택한 시간은 예약할 수 없어요. 다른 시간을 골라 주세요"),
    // result: reservationId
    RESERVATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "RESERVATION_ALREADY_EXISTS", "이 문의에는 이미 예약이 있어요"),
    // result: reservation (최신)
    RESERVATION_VERSION_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_VERSION_CONFLICT", "다른 곳에서 예약이 먼저 바뀌었어요. 최신 내용을 확인해 주세요"),
    // result: change
    CHANGE_IN_PROGRESS(HttpStatus.CONFLICT, "CHANGE_IN_PROGRESS", "진행 중인 변경 요청이 이미 있어요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
