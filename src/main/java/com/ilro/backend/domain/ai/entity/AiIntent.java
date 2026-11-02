package com.ilro.backend.domain.ai.entity;

// 고객 메시지 의도
public enum AiIntent {
    ANSWER, // 답변
    CORRECTION, // 정정
    SIDE_QUESTION, // 다른 질문
    TIME_SELECT, // 시간 선택
    END, // 종료
    COMPLAINT // 불만
}
