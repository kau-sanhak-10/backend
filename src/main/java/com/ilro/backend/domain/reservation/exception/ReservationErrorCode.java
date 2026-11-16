package com.ilro.backend.domain.reservation.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 예약·변경 오류 코드 (명세 3.5) - DB 제약·버전 충돌 대응(3.10), 방문 가능 시간(BE-09·BE-10), 예약 확정·조회(BE-11)
// 변경·취소·차단 시간 코드는 BE-12
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
    // 서비스 지역 밖·주소 좌표가 없음·지도가 판별할 수 없다고 답함 (지도 장애는 MAP_UNAVAILABLE)
    OUT_OF_SERVICE_AREA(HttpStatus.UNPROCESSABLE_CONTENT, "OUT_OF_SERVICE_AREA",
            "방문 지역 확인이 필요해요. 사장님이 확인한 뒤 연락드려요"),
    // 이동시간 계산 불가 (좌표 없는 방문지·지도가 셀 수 없다고 답함) - 시간을 임의로 만들지 않음 (지도 장애는 MAP_UNAVAILABLE)
    TRAVEL_TIME_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "TRAVEL_TIME_UNAVAILABLE",
            "이동 시간을 계산하지 못했어요. 사장님이 확인한 뒤 연락드려요"),
    // 사장님이 직접 응대 중이거나 이미 사장님 확인 단계 - 자동 예약 안 함 (명세에 없음, BE-09에서 추가)
    OWNER_HANDLING(HttpStatus.CONFLICT, "OWNER_HANDLING", "사장님이 확인하고 있는 상담이에요. 사장님이 연락드릴 거예요"),

    // ---------- 잠시 후 다시 - 문의는 사장님 확인으로 넘기지 않고 그대로 ----------

    // 지도 일시 장애 (오류·시간 초과·약속과 다른 값, MapUnavailableException) - 명세에 없음, BE-10 리뷰에서 추가
    MAP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MAP_UNAVAILABLE",
            "지금은 방문 시간을 확인할 수 없어요. 잠시 후 다시 시도해 주세요"),

    // ---------- 시간·예약 ----------

    // result: reason (SlotUnavailableReason)
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "선택한 시간은 예약할 수 없어요. 다른 시간을 골라 주세요"),
    // 없거나 볼 수 없는 예약 (다른 번호·다른 업체·다른 문의) - 있다는 사실도 숨김
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "예약을 찾을 수 없어요"),
    // result: reservationId
    RESERVATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "RESERVATION_ALREADY_EXISTS", "이 문의에는 이미 예약이 있어요"),
    // result: reservation (최신)
    RESERVATION_VERSION_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_VERSION_CONFLICT", "다른 곳에서 예약이 먼저 바뀌었어요. 최신 내용을 확인해 주세요"),
    // result: status - 취소·완료 예약은 시간·주소를 바꾸지 않음 (BE-12)
    RESERVATION_NOT_CHANGEABLE(HttpStatus.CONFLICT, "RESERVATION_NOT_CHANGEABLE", "취소되었거나 작업이 끝난 예약은 바꿀 수 없어요"),
    // result: contactPhone - 완료 예약은 화면에서 취소하지 않고 업체에 문의 (BE-12)
    RESERVATION_COMPLETED(HttpStatus.CONFLICT, "RESERVATION_COMPLETED", "작업이 끝난 예약이에요. 업체에 문의해 주세요"),

    // ---------- 예약 변경 (명세 5.3, BE-12) ----------

    // result: change
    CHANGE_IN_PROGRESS(HttpStatus.CONFLICT, "CHANGE_IN_PROGRESS", "진행 중인 변경 요청이 이미 있어요"),
    // 없거나 볼 수 없는 변경 요청 - 있다는 사실도 숨김
    CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CHANGE_NOT_FOUND", "변경 요청을 찾을 수 없어요"),
    // result: change - 사장님이 시간을 제안하기 전(REQUESTED)에 수락·거절
    CHANGE_NOT_PROPOSED(HttpStatus.CONFLICT, "CHANGE_NOT_PROPOSED", "아직 사장님이 시간을 제안하지 않았어요"),
    // result: change - 이미 끝난 요청·제안 (확정·거절·실패·만료·종료)
    CHANGE_ALREADY_RESOLVED(HttpStatus.CONFLICT, "CHANGE_ALREADY_RESOLVED", "이미 처리가 끝난 변경 요청이에요"),
    // result: change - 제안 기한이 지나 EXPIRED로 바뀜, 기존 예약 유지
    CHANGE_EXPIRED(HttpStatus.CONFLICT, "CHANGE_EXPIRED", "제안 시간이 지났어요. 기존 예약은 그대로예요"),

    // ---------- 차단 시간 (명세 4.6, BE-12) ----------

    // result: reservations (겹친 확정 예약) - acknowledgeConflicts: true로 다시 보내면 저장, 예약은 그대로
    BLOCK_CONFLICTS_RESERVATION(HttpStatus.CONFLICT, "BLOCK_CONFLICTS_RESERVATION", "이 시간에 잡힌 예약이 있어요. 확인 후 다시 저장해 주세요"),
    BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND", "차단 시간을 찾을 수 없어요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
