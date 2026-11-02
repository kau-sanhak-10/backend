package com.ilro.backend.domain.ai.entity;

// AI 판단 결과로 할 일
public enum AiAction {
    ASK_MORE_INFO, // 추가 질문
    REQUEST_IMAGE, // 사진 요청
    ANSWER_SIDE_QUESTION, // 다른 질문 답변
    CHECK_SCHEDULE, // 일정 조회
    COMPLETE, // 정리 완료
    ESCALATE_TO_OWNER, // 사장님 확인
    UNSERVICEABLE // 서비스 불가
}
