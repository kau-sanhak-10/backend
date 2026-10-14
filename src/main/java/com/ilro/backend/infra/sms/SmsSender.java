package com.ilro.backend.infra.sms;

public interface SmsSender {
    // 문자 발송. 성공하면 SENT, 실패하면 FAILED (예외를 던지지 않음)
    // local·dev: LogSmsSender(콘솔에 로그) / prod: RealSmsSender(솔라피, 추후 개발)
    SmsSendResult send(String phone, String message);
}
