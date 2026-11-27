package com.ilro.backend.infra.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;

// 로컬·테스트용 푸시 발송기 - 실제로 보내지 않고 로그만 (VAPID 키가 없어도 서버가 뜨게)
// dev·prod는 VapidWebPushSender
@Slf4j
@Component
@Profile("!dev & !prod")
public class LogWebPushSender implements WebPushSender {

    @Override
    public WebPushResult send(String endpoint) {
        // 구독 주소는 그 자체가 비밀이라 푸시 서버 이름만
        log.info("[LOCAL PUSH] host={}", URI.create(endpoint).getHost());
        return WebPushResult.SENT;
    }
}