package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.boiler.dto.OfficialErrorCodeResponse;
import com.ilro.backend.domain.boiler.service.OfficialErrorCodeLookup;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.OwnerInquiryDetailResponse.FieldDetail;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldKey;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 사장님 문의 상세에 오류코드 공식 설명을 붙임 (AI-14)
// 문의 조회·수정 트랜잭션이 끝난 뒤 컨트롤러에서 부름 - 매뉴얼 조회는 DB 연결을 따로 잡으므로,
// 문의 트랜잭션 안에서 부르면 요청 하나가 연결 둘을 쥐게 되고 동시 요청이 몰리면 연결이 바닥나 서로 기다림
// 오류코드 값이 있는 문의만, 확인된(CONFIRMED) 제조사·모델·오류코드로 찾음
// 미확인·충돌·추정 값은 넘기지 않음 → '공식 설명 미확인', 문의·예약은 그대로
@Component
@RequiredArgsConstructor
public class OfficialErrorCodeAttacher {

    private final OfficialErrorCodeLookup lookup;

    public OwnerInquiryDetailResponse attach(OwnerInquiryDetailResponse detail) {
        return detail.withOfficialErrorCode(find(detail.fields()));
    }

    private OfficialErrorCodeResponse find(List<FieldDetail> fields) {
        Map<String, FieldDetail> byKey = fields.stream()
                .collect(Collectors.toMap(FieldDetail::fieldKey, Function.identity()));
        FieldDetail errorCode = byKey.get(InquiryFieldKey.ERROR_CODE.getKey());
        if (errorCode == null || errorCode.value() == null) {
            return null;
        }
        return lookup.find(confirmedValue(byKey.get(InquiryFieldKey.MANUFACTURER.getKey())),
                confirmedValue(byKey.get(InquiryFieldKey.MODEL.getKey())), confirmedValue(errorCode));
    }

    private static String confirmedValue(FieldDetail field) {
        return field != null && field.status() == InquiryFieldStatus.CONFIRMED ? field.value() : null;
    }
}
