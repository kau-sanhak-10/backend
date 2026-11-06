package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageDeliveryStatus;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 새 메시지 (C-04·C-11) - 오래된 것부터, hasMore면 마지막 messageId를 afterId로 다시 요청
// 전달 실패(FAILED)·처리 중(PENDING)도 그대로 보여 줌 (실패를 성공처럼 표시하지 않음)
public record MessagesResponse(List<MessageItem> messages, boolean hasMore) {

    public record MessageItem(Long messageId, MessageSender sender, String content,
                              MessageDeliveryStatus deliveryStatus, List<Long> photoIds, OffsetDateTime createdAt) {

        public static MessageItem of(Message message, List<Long> photoIds) {
            return new MessageItem(message.getId(), message.getSender(), message.getContent(),
                    message.getDeliveryStatus(), photoIds, KstTime.of(message.getCreatedAt()));
        }
    }
}
