package com.ilro.backend.domain.auth.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 인증·계정 오류 코드 (명세 3.2) - 사장님·운영자 로그인(BE-02)·고객 인증(BE-18)에 쓰는 것, 예약 확정 전 확인(BE-11)·탈퇴(BE-16)
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", "인증 요청을 찾을 수 없어요. 인증번호를 다시 받아 주세요"),
    // result: attemptsLeft
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_MISMATCH", "인증번호가 맞지 않아요"),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "VERIFICATION_EXPIRED", "인증 시간이 지났어요. 인증번호를 다시 받아 주세요"),
    VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_ATTEMPTS_EXCEEDED", "입력 횟수를 넘었어요. 인증번호를 다시 받아 주세요"),
    VERIFICATION_ALREADY_USED(HttpStatus.CONFLICT, "VERIFICATION_ALREADY_USED", "이미 사용한 인증번호예요"),
    // result: retryAfterSeconds
    VERIFICATION_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RESEND_TOO_SOON", "잠시 후 다시 받을 수 있어요"),
    // result: retryAfterSeconds - 번호별 최근 1시간 5회·24시간 10회, IP별 최근 1시간 30회
    VERIFICATION_SEND_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_SEND_LIMITED", "인증번호 요청이 너무 많아요. 잠시 후 다시 시도해 주세요"),
    VERIFICATION_SEND_FAILED(HttpStatus.BAD_GATEWAY, "VERIFICATION_SEND_FAILED", "인증번호 문자를 보내지 못했어요. 다시 시도해 주세요"),
    // 다른 목적·다른 대상의 인증으로 접근 - 예약 조회 토큰으로 상담 복구, 인증 뒤 방문정보 번호가 바뀜 등 (BE-18)
    VERIFICATION_PURPOSE_MISMATCH(HttpStatus.FORBIDDEN, "VERIFICATION_PURPOSE_MISMATCH", "이 인증으로는 할 수 없는 요청이에요. 다시 인증해 주세요"),
    // 예약 확정 전 방문정보 번호 확인(CUSTOMER_VERIFY)이 없거나, 확인한 뒤 번호가 바뀜 (BE-11) - 화면은 고른 시간을 두고 문자 인증으로
    PHONE_NOT_VERIFIED(HttpStatus.FORBIDDEN, "PHONE_NOT_VERIFIED", "예약하려면 휴대전화 번호 확인이 필요해요"),
    OWNER_NOT_REGISTERED(HttpStatus.NOT_FOUND, "OWNER_NOT_REGISTERED", "등록되지 않은 번호예요. 운영팀에 문의해 주세요"),
    OWNER_APPROVAL_PENDING(HttpStatus.FORBIDDEN, "OWNER_APPROVAL_PENDING", "가입 승인을 기다리고 있어요"),
    // result: rejectionReason
    OWNER_REJECTED(HttpStatus.FORBIDDEN, "OWNER_REJECTED", "가입 신청이 거절됐어요"),
    OWNER_RESTRICTED(HttpStatus.FORBIDDEN, "OWNER_RESTRICTED", "이용이 제한된 계정이에요. 운영팀에 문의해 주세요"),
    OWNER_WITHDRAWN(HttpStatus.FORBIDDEN, "OWNER_WITHDRAWN", "탈퇴한 계정이에요"),
    // result: openInquiries, activeReservations - 처리하지 않은 문의(정보 수집·시간 선택·사장님 확인)·확정 예약 수 (BE-16)
    WITHDRAWAL_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "WITHDRAWAL_BLOCKED", "처리하지 않은 문의나 확정 예약이 남아 있어 탈퇴할 수 없어요"),
    // SUPER 운영자만 쓰는 기능(로그인 번호 변경·접근 기록·삭제)을 STAFF가 부름 (BE-17)
    ADMIN_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "이 기능은 최고 운영자만 쓸 수 있습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
