package com.ilro.backend.infra.push;

// 브라우저 푸시 한 건의 결과
public enum WebPushResult {
    // 푸시 서버가 받음 (201 등 2xx)
    SENT,
    // 구독이 없어짐 (404·410) - 사용자가 알림을 끄거나 브라우저 데이터를 지움 → 그 구독을 지움
    GONE,
    // 그 밖의 실패 (키 문제·푸시 서버 장애·시간 초과) → 알림을 다시 보냄
    FAILED
}