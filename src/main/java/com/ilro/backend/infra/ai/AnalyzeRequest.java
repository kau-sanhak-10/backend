package com.ilro.backend.infra.ai;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// 문의 분석 요청 (명세 6.2) - AI 서버는 모르는 칸이 오면 거절하므로 계약에 있는 칸만
// 이름·전화번호·주소는 보내지 않음. 문의의 현재값 기준은 백엔드 DB이고 AI 서버는 상태를 들고 있지 않음
// inputVersion: 요청 시점의 inquiry.ai_input_seq / askCounts: 항목별로 이미 보낸 보완 질문 수
public record AnalyzeRequest(String requestId, Long inquiryId, Long ownerId, long inputVersion,
                             HandlerMode handlerMode, Business business, List<Field> fields,
                             Map<String, Integer> askCounts, List<MessageItem> messages, List<PhotoItem> photos,
                             boolean safetyFlag) {

    public record Business(List<WorkCategory> activeWorkCategories, boolean autoBookingEnabled) {
    }

    // 접수 항목 현재값 - 근거는 사진(photoId) 또는 고객 메시지(messageId)
    public record Field(String fieldKey, String value, InquiryFieldSource source, InquiryFieldStatus status,
                        String unconfirmedReason, Long photoId, Long messageId) {
    }

    // content: 사진만 보낸 메시지는 빈 문자열 (AI 계약상 null 불가)
    public record MessageItem(Long messageId, MessageSender sender, String content, OffsetDateTime createdAt) {
    }

    // imageUrl: 1024px 리사이즈본의 짧은 유효기간 주소
    public record PhotoItem(Long photoId, Long messageId, String imageUrl) {
    }
}
