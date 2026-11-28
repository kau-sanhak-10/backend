package com.ilro.backend.infra.push;

// 브라우저 푸시 발송 (B-26·B-27, BE-14) - 내용 없는 푸시: 브라우저는 고정 문구로 알림을 띄우고 누르면 알림 목록으로
// 예외를 던지지 않고 결과만 돌려줌 (SmsSender와 같은 규칙)
// dev·prod: VapidWebPushSender(실제 발송) / 그 밖(local·테스트): LogWebPushSender(로그만)
public interface WebPushSender {

    WebPushResult send(String endpoint);
}