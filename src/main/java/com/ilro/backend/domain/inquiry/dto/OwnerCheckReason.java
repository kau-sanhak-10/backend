package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;

// 사장님이 확인해야 하는 이유 (B-13) - 사장님 확인 단계일 때만 값이 있고, 여러 개면 급한 것 하나
// 참고 항목 미확인은 이유가 아님 (10/26 기준). 예약 변경 요청은 BE-16에서 추가
public enum OwnerCheckReason {
    // 안전 신호
    SAFETY,
    // 고객이 직접 상담·전화 회신을 요청
    CUSTOMER_REQUEST,
    // 필수 항목(증상)의 값이 서로 다르거나 질문 한도에 닿음
    REQUIRED_INFO_MISSING,
    // AI가 넘김 - 판단이 불명확하거나 AI 호출이 실패함, 자세한 내용은 aiSummary
    AI_HANDOFF;

    public static OwnerCheckReason of(Inquiry inquiry, boolean activeHandoff, boolean requiredWaitingOwner) {
        if (inquiry.getStatus() != InquiryStatus.OWNER_CHECK) {
            return null;
        }
        if (inquiry.isSafetyFlag()) {
            return SAFETY;
        }
        if (activeHandoff) {
            return CUSTOMER_REQUEST;
        }
        return requiredWaitingOwner ? REQUIRED_INFO_MISSING : AI_HANDOFF;
    }
}
