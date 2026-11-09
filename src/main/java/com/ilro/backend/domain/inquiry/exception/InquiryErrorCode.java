package com.ilro.backend.domain.inquiry.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 접수·상담 오류 코드 (명세 3.4) - 사진 코드는 BE-06에서 추가
@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {

    // 없거나 승인 전·탈퇴한 업체의 접수 링크
    INTAKE_LINK_INVALID(HttpStatus.NOT_FOUND, "INTAKE_LINK_INVALID", "접수 링크를 찾을 수 없어요"),
    // result: reservationLookupAvailable - 새 상담만 막고 예약 조회는 됨
    INTAKE_PAUSED(HttpStatus.FORBIDDEN, "INTAKE_PAUSED", "지금은 새 상담을 받지 않아요"),
    // 업체가 설정 중이거나 이용 제한 상태
    OWNER_UNAVAILABLE(HttpStatus.FORBIDDEN, "OWNER_UNAVAILABLE", "지금은 이 업체에 상담을 받을 수 없어요"),
    // result: requiredConsents
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "CONSENT_REQUIRED", "필수 동의가 필요해요"),
    // 없거나 접근 범위 밖의 문의 - 다른 업체 문의도 있다는 사실을 숨기고 404
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다"),
    INQUIRY_CLOSED(HttpStatus.CONFLICT, "INQUIRY_CLOSED", "종료된 상담이에요"),
    // result: currentVersion
    INQUIRY_VERSION_CONFLICT(HttpStatus.CONFLICT, "INQUIRY_VERSION_CONFLICT", "다른 곳에서 접수 정보가 먼저 바뀌었어요. 최신 내용을 확인해 주세요"),
    // 사장님이 고친 방문정보를 고객이 다른 값으로 저장 - 바꿀 내용은 대화로 (B-14 사장님 수정 보호)
    VISIT_INFO_OWNER_CONFIRMED(HttpStatus.CONFLICT, "VISIT_INFO_OWNER_CONFIRMED", "사장님이 확인한 방문 정보예요. 바꿀 내용은 대화로 알려 주세요"),
    // result: reservationId - 확정 예약의 주소·작업에 영향을 주는 수정은 예약 변경 절차로
    RESERVATION_CHANGE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "RESERVATION_CHANGE_REQUIRED", "예약된 방문에 영향을 주는 내용은 예약 변경으로 요청해 주세요"),
    // 직접 응대를 시작하기 전에 사장님 답변
    NOT_IN_OWNER_MODE(HttpStatus.CONFLICT, "NOT_IN_OWNER_MODE", "직접 응대를 시작한 뒤에 답할 수 있어요"),
    // result: reasons (SAFETY·REQUIRED_FIELD)
    AI_RESUME_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "AI_RESUME_BLOCKED", "안전 확인이나 필수 정보가 남아 있어 AI 응대를 다시 켤 수 없어요"),
    // 없거나 다른 업체 문의의 요청
    HANDOFF_NOT_FOUND(HttpStatus.NOT_FOUND, "HANDOFF_NOT_FOUND", "상담 요청을 찾을 수 없습니다"),
    // 끝낸 요청은 다시 진행으로 되돌리지 않음 - 고객이 새로 요청
    HANDOFF_ALREADY_DONE(HttpStatus.CONFLICT, "HANDOFF_ALREADY_DONE", "이미 처리를 마친 요청이에요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
