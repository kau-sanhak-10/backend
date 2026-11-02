package com.ilro.backend.global.common.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ilro.backend.global.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"success", "code", "message", "result"})
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T result;

    // 성공
    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, "OK", "요청 성공", result);
    }

    public static ApiResponse<Void> onSuccess() {
        return new ApiResponse<>(true, "OK", "요청 성공", null);
    }

    // 실패
    public static ApiResponse<Void> onFailure(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> onFailure(ErrorCode errorCode, T result) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), result);
    }
}