package com.ilro.backend.domain.reservation.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 예약·변경 오류 코드 (명세 3.5) - 지금은 DB 제약·버전 충돌 대응(3.10)에 쓰는 것만, 나머지는 BE-11·BE-12에서 추가
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

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
