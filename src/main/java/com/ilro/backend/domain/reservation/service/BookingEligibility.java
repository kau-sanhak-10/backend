package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.entity.InquiryField;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import com.ilro.backend.domain.inquiry.repository.InquiryFieldRepository;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerServiceArea;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.repository.OwnerServiceAreaRepository;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.infra.map.GeoPoint;
import com.ilro.backend.infra.map.ServiceAreaMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 자동 예약 전 업체·문의 확인 (명세 5.2 1·2번, BE-09)
// 고객이 작업 유형을 고르지 않아도 상담은 이어감 - 여기서는 유형이 확인되고 업체가 허용한 경우에만 자동 예약 (WBS BE-09)
// 걸리면 3.5의 422 코드 하나 (명세 순서대로 처음 걸린 것), 문의를 사장님 확인으로 넘기는 것은 부르는 쪽
@Component
@RequiredArgsConstructor
public class BookingEligibility {

    public static final String NOT_PROVIDED = "NOT_PROVIDED";

    private final InquiryFieldRepository fieldRepository;
    private final OwnerServiceAreaRepository serviceAreaRepository;
    private final MapLookup mapLookup;

    // 막힌 이유 - result는 응답의 result (없으면 null)
    public record Block(ReservationErrorCode code, Object result) {
    }

    public Optional<Block> check(Inquiry inquiry) {
        Owner owner = inquiry.getOwner();
        // 1. 업체 - 승인·이용 상태는 문의 쓰기 확인(InquiryAccess.checkWritable)에서 403
        //    신규 접수 중지 중에는 진행 중 상담은 이어가되 새 방문 시간은 사장님이 정함
        if (!owner.isAutoBookingEnabled() || owner.isIntakePaused()) {
            return block(ReservationErrorCode.AUTO_BOOKING_DISABLED);
        }
        // 2. 문의 - 작업 유형
        OwnerWorkType workType = inquiry.getWorkType();
        if (workType == null || !workType.isActive() || !workType.getOwner().getId().equals(owner.getId())) {
            return block(ReservationErrorCode.WORK_TYPE_NOT_CONFIRMED);
        }
        if (!workType.isAutoBookingAllowed()) {
            return block(ReservationErrorCode.AUTO_BOOKING_DISABLED);
        }
        // 필수 항목 (참고 항목은 미확인이어도 예약을 이어감, 10/26 기준)
        List<Map<String, String>> unconfirmed = unconfirmedRequired(inquiry);
        if (!unconfirmed.isEmpty()) {
            return Optional.of(new Block(ReservationErrorCode.REQUIRED_INFO_UNCONFIRMED,
                    Map.of("fields", unconfirmed)));
        }
        if (inquiry.isSafetyFlag()) {
            return block(ReservationErrorCode.SAFETY_CHECK_REQUIRED);
        }
        // 주소 좌표·서비스 지역 - 좌표가 없거나 지도가 판별하지 못하면(지도 오류 포함) 지역 밖과 같이 사장님 확인
        Optional<GeoPoint> destination = destination(inquiry);
        List<String> regionCodes = serviceAreaRepository.findByOwnerIdOrderByIdAsc(owner.getId()).stream()
                .map(OwnerServiceArea::getRegionCode)
                .toList();
        if (destination.isEmpty() || regionCodes.isEmpty()
                || mapLookup.serviceArea(destination.get(), regionCodes) != ServiceAreaMatch.INSIDE) {
            return block(ReservationErrorCode.OUT_OF_SERVICE_AREA);
        }
        return Optional.empty();
    }

    public static Optional<GeoPoint> destination(Inquiry inquiry) {
        if (inquiry.getAddress() == null || inquiry.getAddress().isBlank()) {
            return Optional.empty();
        }
        return GeoPoint.of(inquiry.getLat(), inquiry.getLng());
    }

    // 필수 항목마다 확인됐는지 - 행이 없으면 NOT_PROVIDED, 미확인이면 그 이유
    private List<Map<String, String>> unconfirmedRequired(Inquiry inquiry) {
        Map<String, InquiryField> current = fieldRepository.findCurrent(inquiry.getId()).stream()
                .collect(Collectors.toMap(InquiryField::getFieldKey, Function.identity(), (a, b) -> b));
        List<Map<String, String>> result = new ArrayList<>();
        for (String key : InquiryFieldKey.requiredKeys()) {
            InquiryField field = current.get(key);
            if (field != null && field.getStatus() == InquiryFieldStatus.CONFIRMED) {
                continue;
            }
            String reason = field == null || field.getUnconfirmedReason() == null
                    ? NOT_PROVIDED : field.getUnconfirmedReason();
            Map<String, String> item = new LinkedHashMap<>();
            item.put("fieldKey", key);
            item.put("reason", reason);
            result.add(item);
        }
        return result;
    }

    private static Optional<Block> block(ReservationErrorCode code) {
        return Optional.of(new Block(code, null));
    }
}
