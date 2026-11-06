package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;
import java.util.List;

// 접수정보 (C-08) - 항목별 현재값·출처·확인 여부, 이전 값 이력은 사장님 화면(B-14)에서
public record InquiryFieldsResponse(int version, List<FieldValue> fields) {

    public record FieldValue(String fieldKey, String value, InquiryFieldSource source, InquiryFieldStatus status,
                             String unconfirmedReason, OffsetDateTime updatedAt) {

        public static FieldValue from(InquiryField field) {
            return new FieldValue(field.getFieldKey(), field.getFieldValue(), field.getSource(), field.getStatus(),
                    field.getUnconfirmedReason(), KstTime.of(field.getCreatedAt()));
        }
    }

    public static InquiryFieldsResponse of(int version, List<InquiryField> fields) {
        return new InquiryFieldsResponse(version, fields.stream().map(FieldValue::from).toList());
    }
}
