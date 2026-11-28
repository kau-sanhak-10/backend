package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

// 사장님 시간 제안 (B-19·B-21, 명세 5.3 2단계)
// changeId: 고객 변경 요청에 답할 때 (없으면 업체 사유 변경 - 새로 PROPOSED)
// applyAddressChange: 고객이 요청한 주소로 바꿔서 제안 (지역·이동시간을 그 주소로 검사)
// note: 고객에게 보일 한마디 (알림 문구용, 저장 칸 없음), baseVersion은 화면이 본 예약 버전
public record ChangeProposalRequest(Long changeId, @NotNull OffsetDateTime proposedStartAt,
                                    Boolean applyAddressChange, @Size(max = 255) String note,
                                    @NotNull Integer baseVersion) {

    // applyAddressChange는 빼면 false
    public ChangeProposalRequest {
        applyAddressChange = Boolean.TRUE.equals(applyAddressChange);
    }
}
