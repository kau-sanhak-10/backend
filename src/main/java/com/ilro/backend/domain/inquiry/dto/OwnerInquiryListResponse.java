package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Message;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 사장님 문의 목록 (B-12) - 최근 문의부터, 삭제된 문의 제외
// 목록 공통 형식 (명세 1.2) - page는 0부터, isLast가 아니면 page+1로 다음 쪽을 요청
public record OwnerInquiryListResponse(List<Item> content, int page, int size, long totalElements, int totalPages,
                                       boolean isLast) {

    // 입력 전인 칸은 null
    // ownerCheckReason: 사장님 확인 단계일 때 그 이유
    // handoff: 진행 중 직접 상담·전화 회신 요청, lastMessage: 마지막 메시지 앞부분
    public record Item(Long inquiryId, InquiryStatus status, HandlerMode handlerMode, boolean safetyFlag,
                       OwnerCheckReason ownerCheckReason, String customerName, String customerPhone, String address, WorkCategory workCategory,
                       String symptom, HandoffResponse handoff, LastMessage lastMessage, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt) {

        public static Item of(Inquiry inquiry, HandoffRequest handoff, Message lastMessage,
                              boolean requiredWaitingOwner) {
            Customer customer = inquiry.getCustomer();
            return new Item(inquiry.getId(), inquiry.getStatus(), inquiry.getHandlerMode(), inquiry.isSafetyFlag(),
                    OwnerCheckReason.of(inquiry, handoff != null, requiredWaitingOwner),
                    customer == null ? null : customer.getName(), customer == null ? null : customer.getPhone(),
                    inquiry.getAddress(),
                    inquiry.getWorkType() == null ? null : inquiry.getWorkType().getWorkCategory(),
                    inquiry.getSymptom(), handoff == null ? null : HandoffResponse.from(handoff),
                    lastMessage == null ? null : LastMessage.from(lastMessage), KstTime.of(inquiry.getCreatedAt()),
                    KstTime.of(inquiry.getUpdatedAt()));
        }
    }

    // preview: 내용 앞 50자 - 사진만 보낸 메시지면 null
    public record LastMessage(MessageSender sender, String preview, OffsetDateTime createdAt) {

        private static final int PREVIEW_LENGTH = 50;

        public static LastMessage from(Message message) {
            String content = message.getContent();
            // 글자 단위로 자름 - 이모지가 반으로 잘리지 않게
            boolean shortEnough = content == null || content.codePointCount(0, content.length()) <= PREVIEW_LENGTH;
            return new LastMessage(message.getSender(),
                    shortEnough ? content : content.substring(0, content.offsetByCodePoints(0, PREVIEW_LENGTH)),
                    KstTime.of(message.getCreatedAt()));
        }
    }
}
