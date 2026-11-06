package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.InquiryFieldsResponse;
import com.ilro.backend.domain.inquiry.dto.UpdateFieldsRequest;
import com.ilro.backend.domain.inquiry.dto.UpdateFieldsRequest.FieldChange;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.repository.InquiryFieldRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 고객 접수정보 확인·수정 (C-08, 명세 2.2·4.2)
// 고친 값은 출처 CUSTOMER로 새 행을 넣고 이전 값은 is_current=false로 남김 (이력 보존, B-14)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryFieldService {

    // 미확인 이유 (명세 2.2 제안 값)
    static final String CUSTOMER_UNSURE = "CUSTOMER_UNSURE";
    static final String CONFLICT = "CONFLICT";

    private final InquiryAccess inquiryAccess;
    private final InquiryFieldRepository fieldRepository;

    private record Change(InquiryFieldKey key, String value, boolean unsure) {
    }

    public InquiryFieldsResponse getFields(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.find(principal, inquiryId);
        InquiryFieldsResponse response = InquiryFieldsResponse.of(inquiry.getVersion(),
                fieldRepository.findCurrent(inquiry.getId()));
        AccessLog.record(principal, "VIEW", "inquiry.fields", inquiryId);
        return response;
    }

    // 내용이 그대로인 항목은 건너뜀 - 하나도 안 바뀌면 version·AI 입력 순번도 그대로
    @Transactional
    public InquiryFieldsResponse updateFields(AuthPrincipal principal, Long inquiryId, UpdateFieldsRequest request) {
        List<Change> changes = toChanges(request.fields());

        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        inquiryAccess.checkVersion(inquiry, request.version());

        Map<String, InquiryField> current = fieldRepository.findCurrent(inquiry.getId()).stream()
                .collect(Collectors.toMap(InquiryField::getFieldKey, Function.identity()));
        List<InquiryField> newRows = new ArrayList<>();
        for (Change change : changes) {
            newRow(inquiry, change, current.get(change.key().getKey())).ifPresent(newRows::add);
        }
        if (!newRows.isEmpty()) {
            int versionBefore = inquiry.getVersion();
            fieldRepository.retireCurrent(inquiry.getId(),
                    newRows.stream().map(InquiryField::getFieldKey).toList());
            fieldRepository.saveAll(newRows);
            for (InquiryField row : newRows) {
                inquiry.applyField(InquiryFieldKey.fromKey(row.getFieldKey()).orElseThrow(), row.getFieldValue());
            }
            inquiryAccess.markInfoChanged(inquiry, versionBefore);
            AccessLog.record(principal, "UPDATE", "inquiry.fields", inquiryId);
        }
        return InquiryFieldsResponse.of(inquiry.getVersion(), fieldRepository.findCurrent(inquiry.getId()));
    }

    // 사장님이 정한 값은 '모름'이나 같은 값으로 덮지 않음. 다른 값으로 고치면 저장하되 미확인(CONFLICT)으로 두어 사장님이 다시 확인
    // 그 밖에 고객이 모른다고 하면 미확인(CUSTOMER_UNSURE)
    private static Optional<InquiryField> newRow(Inquiry inquiry, Change change, InquiryField before) {
        boolean ownerValue = before != null && before.getSource() == InquiryFieldSource.OWNER;
        if (ownerValue && (change.value() == null || Objects.equals(before.getFieldValue(), change.value()))) {
            return Optional.empty();
        }
        InquiryFieldStatus status = InquiryFieldStatus.CONFIRMED;
        String reason = null;
        if (ownerValue) {
            status = InquiryFieldStatus.UNCONFIRMED;
            reason = CONFLICT;
        } else if (change.unsure()) {
            status = InquiryFieldStatus.UNCONFIRMED;
            reason = CUSTOMER_UNSURE;
        }
        if (before != null && Objects.equals(before.getFieldValue(), change.value()) && before.getStatus() == status
                && Objects.equals(before.getUnconfirmedReason(), reason)) {
            return Optional.empty();
        }
        return Optional.of(InquiryField.builder()
                .inquiry(inquiry)
                .fieldKey(change.key().getKey())
                .fieldValue(change.value())
                .source(InquiryFieldSource.CUSTOMER)
                .status(status)
                .unconfirmedReason(reason)
                .build());
    }

    // 모르는 항목·같은 항목 두 번·칸 길이 초과·값도 unsure도 없음 → 칸별 400
    private static List<Change> toChanges(List<FieldChange> fields) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Set<InquiryFieldKey> seen = EnumSet.noneOf(InquiryFieldKey.class);
        for (int i = 0; i < fields.size(); i++) {
            FieldChange field = fields.get(i);
            String path = "fields[" + i + "]";
            Optional<InquiryFieldKey> key = InquiryFieldKey.fromKey(field.fieldKey());
            if (key.isEmpty()) {
                errors.add(new FieldErrorResponse(path + ".fieldKey", "고칠 수 없는 항목이에요"));
                continue;
            }
            if (!seen.add(key.get())) {
                errors.add(new FieldErrorResponse(path + ".fieldKey", "같은 항목을 두 번 보냈어요"));
                continue;
            }
            String value = field.value() == null ? null : field.value().strip();
            boolean unsure = Boolean.TRUE.equals(field.unsure());
            if (value != null && value.length() > key.get().getMaxLength()) {
                errors.add(new FieldErrorResponse(path + ".value", key.get().getMaxLength() + "자 이하여야 합니다"));
            } else if (value == null && !unsure) {
                errors.add(new FieldErrorResponse(path + ".value", "값을 보내거나, 모르면 unsure를 true로 보내 주세요"));
            } else {
                changes.add(new Change(key.get(), value, unsure));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        return changes;
    }
}
