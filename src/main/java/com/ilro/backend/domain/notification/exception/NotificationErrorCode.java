package com.ilro.backend.domain.notification.exception;

import com.ilro.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 알림 오류 코드 (BE-14)
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    // 없는 알림·다른 업체 알림·앱 안 알림이 아닌 행(푸시·문자) - 있다는 사실도 숨김 (B-26)
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없어요");

    private final HttpStatus status;
    private final String code;
    private final String message;
}