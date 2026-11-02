package com.ilro.backend.domain.ai.entity;

// AI 호출 단계
public enum AiStage {
    EXTRACT, // 사진·정보 추출
    DECIDE, // 상황 판단
    SUMMARY // 요약
}
