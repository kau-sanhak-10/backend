package com.ilro.backend.domain.inquiry.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 사진 업로드·보기 오류 코드 (C-05) - 화면이 코드별로 다른 안내를 보여 줌
@Getter
@RequiredArgsConstructor
public enum PhotoErrorCode implements ErrorCode {

    PHOTO_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "PHOTO_TYPE_NOT_ALLOWED", "JPG·PNG 사진만 보낼 수 있어요"),
    PHOTO_TOO_LARGE(HttpStatus.BAD_REQUEST, "PHOTO_TOO_LARGE", "사진 한 장은 10MB 이하로 보내 주세요"),
    // 업로드 중·완료 사진 기준 (실패한 사진은 세지 않음)
    PHOTO_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "PHOTO_LIMIT_EXCEEDED", "문의당 사진은 10장까지 보낼 수 있어요"),
    // 없거나 다른 문의의 사진 - 있다는 사실도 숨김
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없어요"),
    // complete를 업로드보다 먼저 부름 - 상태는 그대로 두므로 업로드 후 다시 부르면 됨
    PHOTO_NOT_UPLOADED(HttpStatus.CONFLICT, "PHOTO_NOT_UPLOADED", "아직 사진이 올라가지 않았어요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}