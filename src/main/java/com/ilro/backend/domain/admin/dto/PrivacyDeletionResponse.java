package com.ilro.backend.domain.admin.dto;

import java.util.List;

// 개인정보 삭제 결과 (O-09, 명세 4.8) - 처리된 범위(deleted)와 실패한 범위(failed)를 나눠 돌려줌
public record PrivacyDeletionResponse(List<Deleted> deleted, List<Failed> failed) {

    // messages·photos·fields: 지운 대화 내용·사진·접수 항목 수
    // customerDeleted: 고객 이름·번호까지 지웠는지 - 예약 기록이나 다른 문의가 있는 고객은 남김
    // kept: 남긴 것과 그 이유 - RESERVATION_RECORD(예약 기록과 그 고객), CONSENT_RECORD(동의 기록)
    public record Deleted(Long inquiryId, int messages, int photos, int fields, boolean customerDeleted,
                          List<Kept> kept) {
    }

    public enum Kept {
        RESERVATION_RECORD, CONSENT_RECORD
    }

    // reason: PHOTO_DELETE_FAILED(사진 파일 삭제 실패)·DATA_DELETE_FAILED(기록 삭제 실패)
    //   또는 확인한 뒤 상태가 바뀜 - OPEN_INQUIRY·ACTIVE_RESERVATION·INQUIRY_NOT_FOUND
    public record Failed(Long inquiryId, String reason) {
    }
}
