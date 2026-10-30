package com.ilro.backend.domain.auth.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 인증·계정 오류 코드 (명세 3.2) - 지금은 사장님·운영자 로그인(BE-02)에 쓰는 것만, 고객 인증·탈퇴는 BE-18·BE-04에서 추가
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
    OWNER_NOT_REGISTERED(HttpStatus.NOT_FOUND, "OWNER_NOT_REGISTERED", "등록되지 않은 번호예요. 운영팀에 문의해 주세요"),
    OWNER_APPROVAL_PENDING(HttpStatus.FORBIDDEN, "OWNER_APPROVAL_PENDING", "가입 승인을 기다리고 있어요"),
    // result: rejectionReason
    OWNER_REJECTED(HttpStatus.FORBIDDEN, "OWNER_REJECTED", "가입 신청이 거절됐어요"),
    OWNER_RESTRICTED(HttpStatus.FORBIDDEN, "OWNER_RESTRICTED", "이용이 제한된 계정이에요. 운영팀에 문의해 주세요"),
    OWNER_WITHDRAWN(HttpStatus.FORBIDDEN, "OWNER_WITHDRAWN", "탈퇴한 계정이에요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
