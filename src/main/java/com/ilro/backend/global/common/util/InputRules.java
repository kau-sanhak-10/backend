package com.ilro.backend.global.common.util;

// 요청 값 공통 규칙 (명세 1.2) - 값이 없으면 null, 빈 문자열·공백만 있는 문자열은 400
// 쓰는 법: @Pattern(regexp = InputRules.NOT_BLANK, message = InputRules.NOT_BLANK_MESSAGE) - null은 통과
public final class InputRules {

    public static final String NOT_BLANK = "(?s).*\\S.*";
    public static final String NOT_BLANK_MESSAGE = "빈 값 대신 null을 보내 주세요";

    private InputRules() {
    }
}
