package com.ilro.backend.domain.boiler.dto;

import com.ilro.backend.domain.boiler.entity.OfficialManual;
import com.ilro.backend.domain.boiler.entity.OfficialManualErrorCode;
import com.ilro.backend.domain.boiler.entity.OfficialManualModel;

import java.time.LocalDate;

// 오류코드 공식 설명 (AI-14, 사장님 문의 상세 B-13) - 진단이 아니라 제조사 공식 매뉴얼에 적힌 내용과 출처
// MATCHED: 확인된 제조사·모델·오류코드가 셋 다 공식 매뉴얼과 맞음 - 매뉴얼의 오류 이름·설명·출처를 그대로
// UNCONFIRMED: '공식 설명 미확인' - reason만 채우고 나머지는 null, 문의·예약은 그대로 이어감
// AI 해석(aiSummary.interpretations)과 따로 둠 - 해석은 '참고', 이것은 매뉴얼 원문
public record OfficialErrorCodeResponse(Status status, Reason reason, String errorCode, String modelName,
                                        String title, String description, ManualSource manual) {

    public enum Status {
        MATCHED,
        UNCONFIRMED
    }

    public enum Reason {
        FIELD_NOT_CONFIRMED, // 제조사·모델·오류코드 중 확인되지 않은 값이 있음 (값 없음·고객이 모름·충돌·추정)
        MODEL_NOT_FOUND,     // 공식 매뉴얼을 갖고 있지 않은 제조사·모델 (다른 모델·구버전·자료 없음)
        CODE_NOT_FOUND,      // 그 모델의 매뉴얼에 없는 오류코드
        LOOKUP_FAILED        // 매뉴얼 조회 실패
    }

    // 출처 - 자료실(sourceUrl)에서 productName으로 찾아 page쪽을 보면 됨
    // fileUrl: 자료실의 내려받기 주소 (자료실을 거치지 않으면 제조사 사이트가 막을 수 있어 sourceUrl을 먼저 보여 줌)
    public record ManualSource(String productName, String documentName, String documentVersion, int page,
                               String sourceUrl, String fileUrl, LocalDate checkedOn) {
    }

    // errorCode·modelName은 매뉴얼에 적힌 꼴 (예: E001, 거꾸로 ECO 콘덴싱 L20-15HFN)
    public static OfficialErrorCodeResponse matched(OfficialManualModel model, OfficialManualErrorCode row) {
        OfficialManual manual = row.getManual();
        return new OfficialErrorCodeResponse(Status.MATCHED, null, row.getCode(), model.getModelName(),
                row.getTitle(), row.getDescription(),
                new ManualSource(manual.getProductName(), manual.getDocumentName(), manual.getDocumentVersion(),
                        row.getPage(), manual.getSourceUrl(), manual.getFileUrl(), manual.getCheckedOn()));
    }

    public static OfficialErrorCodeResponse unconfirmed(Reason reason) {
        return new OfficialErrorCodeResponse(Status.UNCONFIRMED, reason, null, null, null, null, null);
    }
}
