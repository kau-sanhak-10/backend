package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageDeliveryStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// aiStatus: PROCESSING이면 AI 답변을 메시지 가져오기로 받음, 사장님 응대 중이면 NONE (명세 4.2·6.1)
public record SendMessageResponse(Long messageId, MessageDeliveryStatus deliveryStatus, AiStatus aiStatus,
                                  OffsetDateTime createdAt) {

    public enum AiStatus {
        PROCESSING,
        NONE
    }

    public static SendMessageResponse of(Message message, AiStatus aiStatus) {
        return new SendMessageResponse(message.getId(), message.getDeliveryStatus(), aiStatus,
                KstTime.of(message.getCreatedAt()));
    }
}
