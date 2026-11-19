package com.ilro.backend.domain.notification.dto;

// Web Push 공개키 (B-27, 명세 5.6) - 브라우저가 구독을 만들 때 applicationServerKey로 넘김
public record PushPublicKeyResponse(String publicKey) {
}