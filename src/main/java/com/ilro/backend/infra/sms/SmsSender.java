package com.ilro.backend.infra.sms;

public interface SmsSender {
    // 문자 발송. 성공하면 SENT, 실패하면 FAILED (예외를 던지지 않음)
    // local: LogSmsSender(콘솔에 로그) / dev·prod: RealSmsSender(솔라피)
    SmsSendResult send(String phone, String message);
}
