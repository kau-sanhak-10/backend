package com.ilro.backend.domain.owner.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

// 접수 링크 토큰 생성 - 주소에 들어가므로 URL에 안전한 문자만 씀
// 업체 등록(BE-17)에서도 같은 생성기를 사용
@Component
public class IntakeLinkTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}