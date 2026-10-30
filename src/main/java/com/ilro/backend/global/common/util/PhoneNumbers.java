package com.ilro.backend.global.common.util;

import java.util.regex.Pattern;

// 전화번호 정규화 - 010-1234-5678·01012345678 모두 받아 숫자만으로 저장·비교 (명세 1.2)
public final class PhoneNumbers {

    private static final Pattern MOBILE = Pattern.compile("01[016789]\\d{7,8}");
    // 업체 대표 연락처 - 휴대전화·지역번호(02-123-4567)·대표번호(1588-1234)
    private static final Pattern CONTACT = Pattern.compile("0\\d{8,10}|1[5-9]\\d{6}");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    private PhoneNumbers() {
    }

    // 숫자만 남김, null이면 null
    public static String normalize(String phone) {
        return phone == null ? null : NON_DIGIT.matcher(phone).replaceAll("");
    }

    // 정규화한 번호가 휴대전화 번호인지
    public static boolean isMobile(String normalized) {
        return normalized != null && MOBILE.matcher(normalized).matches();
    }

    // 정규화한 번호가 고객이 걸 수 있는 연락처인지
    public static boolean isContactNumber(String normalized) {
        return normalized != null && CONTACT.matcher(normalized).matches();
    }

    // 로그용 가린 번호 010-****-5678 (명세 1.8)
    public static String mask(String phone) {
        String digits = normalize(phone);
        if (digits == null || digits.length() < 8) {
            return "****";
        }
        return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
    }
}
