package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.inquiry.dto.InquiryDetailResponse.LinkedReservation;
import com.ilro.backend.domain.inquiry.dto.InquiryDetailResponse.VisitInfo;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.HandoffRequest;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.entity.Photo;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.global.common.util.KstTime;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 사장님 문의 상세 (B-13) - 고객·방문정보, 항목별 값·출처·확인 여부·미확인 이유·수정 이력,
// AI 요약(사실·해석 구분), 사진, 상담·회신 요청, 연결 예약
// 대화는 따로 가져옴 (GET …/messages), version은 수정 때 그대로 보냄 (명세 1.7)
public record OwnerInquiryDetailResponse(Long inquiryId, InquiryStatus status, HandlerMode handlerMode,
                                         boolean safetyFlag, OwnerCheckReason ownerCheckReason, int version,
                                         VisitInfo visitInfo,
                                         boolean phoneVerified, List<FieldDetail> fields, AiSummary aiSummary,
                                         List<PhotoItem> photos, List<OwnerHandoffResponse> handoffRequests,
                                         LinkedReservation reservation, List<ConsentType> consents,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    // 사실: 항목별 현재값 - 접수 항목은 모두 보여 줌, 아직 값이 없으면 미확인(NOT_PROVIDED)·출처 null
    // required: 필수 항목(증상) - 참고 항목은 미확인이어도 상담·예약을 이어감
    // photoId·messageId: 값의 근거, history: 이전 값 (오래된 것부터)
    public record FieldDetail(String fieldKey, boolean required, String value, InquiryFieldSource source,
                              InquiryFieldStatus status, String unconfirmedReason, Long photoId, Long messageId,
                              OffsetDateTime updatedAt, List<FieldHistory> history) {
    }

    public record FieldHistory(String value, InquiryFieldSource source, InquiryFieldStatus status,
                               String unconfirmedReason, OffsetDateTime createdAt) {

        static FieldHistory from(InquiryField field) {
            return new FieldHistory(field.getFieldValue(), field.getSource(), field.getStatus(),
                    field.getUnconfirmedReason(), KstTime.of(field.getCreatedAt()));
        }
    }

    // 해석: 적용된 마지막 AI 판단의 해석·충돌 후보·안전 이유·다음 행동 (AI 응답 원본 그대로)
    // summary: AI 요약 문장 - 만들지 못했으면 null. AI 판단도 요약도 없으면 aiSummary 자체가 null
    public record AiSummary(String summary, List<JsonNode> interpretations, List<JsonNode> conflicts,
                            List<String> safetyReasons, AiAction nextAction, String nextActionReason,
                            OffsetDateTime decidedAt) {
    }

    // 업로드를 마친 사진 - 보기용 주소는 GET …/photos/{photoId}/url (BE-06)
    public record PhotoItem(Long photoId, Long messageId, String mimeType, OffsetDateTime createdAt) {

        static PhotoItem from(Photo photo) {
            return new PhotoItem(photo.getId(), photo.getMessage() == null ? null : photo.getMessage().getId(),
                    photo.getMimeType(), KstTime.of(photo.getCreatedAt()));
        }
    }

    // fields: 이 문의의 항목 행 전부 (현재값·이력), handoffs: 최근 것부터
    public static OwnerInquiryDetailResponse of(Inquiry inquiry, List<InquiryField> fields, AiSummary aiSummary,
                                                List<Photo> photos, List<HandoffRequest> handoffs,
                                                Reservation reservation, List<ConsentType> consents,
                                                boolean requiredWaitingOwner) {
        Customer customer = inquiry.getCustomer();
        boolean activeHandoff = handoffs.stream().anyMatch(handoff -> HandoffRequest.ACTIVE.contains(handoff.getStatus()));
        VisitInfo visitInfo = new VisitInfo(customer == null ? null : customer.getName(),
                customer == null ? null : customer.getPhone(), inquiry.getAddress(), inquiry.getAddressDetail(),
                inquiry.getLat(), inquiry.getLng(), inquiry.getAccessNote(),
                inquiry.getWorkType() == null ? null : inquiry.getWorkType().getWorkCategory());
        return new OwnerInquiryDetailResponse(inquiry.getId(), inquiry.getStatus(), inquiry.getHandlerMode(),
                inquiry.isSafetyFlag(), OwnerCheckReason.of(inquiry, activeHandoff, requiredWaitingOwner),
                inquiry.getVersion(), visitInfo, inquiry.getPhoneVerifiedAt() != null,
                fieldDetails(fields), aiSummary, photos.stream().map(PhotoItem::from).toList(),
                handoffs.stream().map(OwnerHandoffResponse::from).toList(), LinkedReservation.from(reservation),
                consents, KstTime.of(inquiry.getCreatedAt()), KstTime.of(inquiry.getUpdatedAt()));
    }

    private static List<FieldDetail> fieldDetails(List<InquiryField> fields) {
        Map<String, List<InquiryField>> byKey = fields.stream()
                .collect(Collectors.groupingBy(InquiryField::getFieldKey));
        return Arrays.stream(InquiryFieldKey.values())
                .map(key -> fieldDetail(key, byKey.getOrDefault(key.getKey(), List.of())))
                .toList();
    }

    // rows는 오래된 것부터 - 같은 요청에서 방금 고친 항목은 이전 행이 메모리에 아직 현재값으로 남아 있을 수 있어
    // (이력으로 내리는 일괄 UPDATE는 읽어 둔 Entity를 바꾸지 않음) 가장 최근의 현재값 행을 고름
    private static FieldDetail fieldDetail(InquiryFieldKey key, List<InquiryField> rows) {
        InquiryField current = rows.stream().filter(InquiryField::isCurrent).reduce((first, second) -> second)
                .orElse(null);
        List<FieldHistory> history = rows.stream()
                .filter(row -> row != current)
                .map(FieldHistory::from)
                .toList();
        if (current == null) {
            return new FieldDetail(key.getKey(), key.isRequired(), null, null, InquiryFieldStatus.UNCONFIRMED,
                    "NOT_PROVIDED", null, null, null, history);
        }
        return new FieldDetail(key.getKey(), key.isRequired(), current.getFieldValue(), current.getSource(),
                current.getStatus(), current.getUnconfirmedReason(),
                current.getPhoto() == null ? null : current.getPhoto().getId(),
                current.getMessage() == null ? null : current.getMessage().getId(),
                KstTime.of(current.getCreatedAt()), history);
    }
}
