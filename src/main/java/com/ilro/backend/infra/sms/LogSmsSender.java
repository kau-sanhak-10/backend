package com.ilro.backend.infra.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"local", "dev"})
public class LogSmsSender implements SmsSender {

    @Override
    public SmsSendResult send(String phone, String message) {
        log.info("[가짜 문자] 발송 요청 처리 (수신번호·본문 기록 안 함)");
        return SmsSendResult.SENT;
    }
}
