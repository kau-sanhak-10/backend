package com.ilro.backend.domain.ai.entity;

// AI 호출 결과 - 실패 시 사장님 확인 전환·재처리 대상 (C09·O08)
public enum AiDecisionStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    INVALID_OUTPUT, // 모델 출력이 형식에 안 맞음 (다시 요청 1회 후에도)
    STALE // 결과가 오는 사이 새 입력이 들어와 적용하지 않음
}
