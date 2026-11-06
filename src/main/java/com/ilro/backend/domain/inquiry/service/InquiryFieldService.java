package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.inquiry.dto.InquiryFieldsResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerUpdateInquiryRequest.OwnerFieldChange;
import com.ilro.backend.domain.inquiry.dto.UpdateFieldsRequest;
import com.ilro.backend.domain.inquiry.dto.UpdateFieldsRequest.FieldChange;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.repository.InquiryFieldRepository;
import com.ilro.backend.domain.notification.service.NeedsCheckNotifier;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 접수정보 확인·수정 - 고객(C-08)·사장님(B-14) (명세 2.2·4.2)
// 고친 값은 출처 CUSTOMER·OWNER로 새 행을 넣고 이전 값은 is_current=false로 남김 (이력 보존, B-14)
// inquiry_field가 항목별 기준 - 같은 저장 안에서 문의 칸(inquiry.symptom 등)에도 반영
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryFieldService {

    // 미확인 이유 (명세 2.2 제안 값)
    static final String CUSTOMER_UNSURE = "CUSTOMER_UNSURE";

    // 필수 항목이 사장님 판단을 기다리는 미확인 이유 - 서로 다른 값·질문 한도 도달 (AI가 더 물어도 소용없음)
    // 아직 말하지 않음(NOT_PROVIDED)·고객이 잘 모름·사진으로 못 읽음은 AI가 이어서 물어봄
    private static final Set<String> WAITING_OWNER_REASONS = Set.of(InquiryField.CONFLICT, "ASK_LIMIT_REACHED");

    private final InquiryAccess inquiryAccess;
    private final InquiryFieldRepository fieldRepository;
    private final NeedsCheckNotifier needsCheckNotifier;

    private record Change(InquiryFieldKey key, String value, boolean unsure) {
    }

    // 필수 항목이 사장님 판단을 기다리는 문의 (B-12·B-13·B-16)
    public Set<Long> findRequiredWaitingOwner(Collection<Long> inquiryIds) {
        if (inquiryIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(fieldRepository.findInquiryIdsWith(inquiryIds, InquiryFieldKey.requiredKeys(),
                InquiryFieldStatus.UNCONFIRMED, WAITING_OWNER_REASONS));
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
            replaceCurrent(inquiry, newRows);
            askOwnerIfRequiredConflict(inquiry, newRows, current);
            inquiryAccess.markInfoChanged(inquiry, versionBefore);
            AccessLog.record(principal, "UPDATE", "inquiry.fields", inquiryId);
        }
        return InquiryFieldsResponse.of(inquiry.getVersion(), fieldRepository.findCurrent(inquiry.getId()));
    }

    // 필수 항목이 사장님 값과 달라지면 사장님 확인으로 - AI가 이어서 답하거나 예약으로 넘어가지 않게
    // 알림은 사장님 값이 충돌로 바뀔 때 한 번 (충돌 중에 고객이 또 고치면 다시 알리지 않음)
    // 단계가 바뀌면 처리 중이던 AI 결과는 적용되지 않음
    private void askOwnerIfRequiredConflict(Inquiry inquiry, List<InquiryField> newRows,
                                            Map<String, InquiryField> before) {
        for (InquiryField row : newRows) {
            boolean required = InquiryFieldKey.fromKey(row.getFieldKey()).map(InquiryFieldKey::isRequired).orElse(false);
            InquiryField previous = before.get(row.getFieldKey());
            if (required && row.isOwnerDecision() && previous != null
                    && previous.getSource() == InquiryFieldSource.OWNER) {
                inquiry.moveToOwnerCheck();
                needsCheckNotifier.notify(inquiry, "NEEDS_CHECK:FIELD_CONFLICT:" + row.getId());
            }
        }
    }

    // 사장님 수정 (B-14) - 출처 OWNER·확인됨으로 새 행 (AI·고객이 덮어쓰지 않음)
    // 이미 사장님이 확인한 같은 값이면 건너뜀. 문의를 잠그고 버전을 확인한 뒤 부름
    // 바뀐 항목이 있으면 true - version·AI 입력 순번은 부른 쪽이 올림
    @Transactional
    public boolean writeOwnerValues(Inquiry inquiry, Map<InquiryFieldKey, String> values) {
        Map<String, InquiryField> current = fieldRepository.findCurrent(inquiry.getId()).stream()
                .collect(Collectors.toMap(InquiryField::getFieldKey, Function.identity()));
        List<InquiryField> newRows = new ArrayList<>();
        values.forEach((key, value) -> {
            InquiryField before = current.get(key.getKey());
            if (before != null && before.getSource() == InquiryFieldSource.OWNER
                    && before.getStatus() == InquiryFieldStatus.CONFIRMED
                    && Objects.equals(before.getFieldValue(), value)) {
                return;
            }
            newRows.add(InquiryField.builder()
                    .inquiry(inquiry)
                    .fieldKey(key.getKey())
                    .fieldValue(value)
                    .source(InquiryFieldSource.OWNER)
                    .status(InquiryFieldStatus.CONFIRMED)
                    .build());
        });
        replaceCurrent(inquiry, newRows);
        return !newRows.isEmpty();
    }

    // 모르는 항목·같은 항목 두 번·칸 길이 초과 → 칸별 400 (사장님은 확인한 값만 보냄)
    static Map<InquiryFieldKey, String> toOwnerValues(List<OwnerFieldChange> fields) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        Map<InquiryFieldKey, String> values = new EnumMap<>(InquiryFieldKey.class);
        for (int i = 0; i < fields.size(); i++) {
            OwnerFieldChange field = fields.get(i);
            String path = "fields[" + i + "]";
            Optional<InquiryFieldKey> key = InquiryFieldKey.fromKey(field.fieldKey());
            if (key.isEmpty()) {
                errors.add(new FieldErrorResponse(path + ".fieldKey", "고칠 수 없는 항목이에요"));
            } else if (values.containsKey(key.get())) {
                errors.add(new FieldErrorResponse(path + ".fieldKey", "같은 항목을 두 번 보냈어요"));
            } else if (field.value().strip().length() > key.get().getMaxLength()) {
                errors.add(new FieldErrorResponse(path + ".value", key.get().getMaxLength() + "자 이하여야 합니다"));
            } else {
                values.put(key.get(), field.value().strip());
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        return values;
    }

    // 이전 현재값을 이력으로 내리고 새 행을 현재값으로 - 문의 칸에도 같은 값을 반영
    private void replaceCurrent(Inquiry inquiry, List<InquiryField> newRows) {
        if (newRows.isEmpty()) {
            return;
        }
        fieldRepository.retireCurrent(inquiry.getId(), newRows.stream().map(InquiryField::getFieldKey).toList());
        fieldRepository.saveAll(newRows);
        for (InquiryField row : newRows) {
            inquiry.applyField(InquiryFieldKey.fromKey(row.getFieldKey()).orElseThrow(), row.getFieldValue());
        }
    }

    // 사장님이 정할 값은 '모름'이나 같은 값으로 덮지 않음. 다른 값으로 고치면 저장하되 미확인(CONFLICT)으로 두어 사장님이 다시 확인
    // 충돌 중에 고객이 또 고쳐도 사장님이 정할 때까지 미확인
    // 그 밖에 고객이 모른다고 하면 미확인(CUSTOMER_UNSURE)
    private static Optional<InquiryField> newRow(Inquiry inquiry, Change change, InquiryField before) {
        boolean ownerValue = before != null && before.isOwnerDecision();
        if (ownerValue && (change.value() == null || Objects.equals(before.getFieldValue(), change.value()))) {
            return Optional.empty();
        }
        InquiryFieldStatus status = InquiryFieldStatus.CONFIRMED;
        String reason = null;
        if (ownerValue) {
            status = InquiryFieldStatus.UNCONFIRMED;
            reason = InquiryField.CONFLICT;
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
