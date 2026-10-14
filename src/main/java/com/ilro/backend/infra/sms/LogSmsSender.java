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
        log.info("[가짜 문자] to={}, message={}", mask(phone), message);
        return SmsSendResult.SENT;
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 8) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
