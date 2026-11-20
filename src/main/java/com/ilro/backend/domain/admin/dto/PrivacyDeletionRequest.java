package com.ilro.backend.domain.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

// 개인정보 삭제 요청 (O-09, BE-19) - 문의 단위
// basis: 처리 근거 (필수) - 근거 없이는 지우지 않음
//   CUSTOMER_REQUEST: 고객의 삭제 요청 / RETENTION_EXPIRED: 보관기간이 지남 (보관기간이 정해지기 전에는 422)
// reference: 요청을 받은 기록의 번호 같은 참조 값 (선택) - 로그에 남으므로 이름·전화번호를 넣지 않게 영문·숫자만
public record PrivacyDeletionRequest(
        @NotEmpty(message = "필수 값입니다") @Size(max = 50, message = "한 번에 50건까지 보낼 수 있습니다")
        List<@NotNull(message = "필수 값입니다") Long> inquiryIds,
        @NotNull(message = "필수 값입니다") Basis basis,
        @Pattern(regexp = "[A-Za-z0-9_-]{1,50}", message = "영문·숫자·-·_ 50자 이하여야 합니다")
        String reference) {

    public enum Basis {
        CUSTOMER_REQUEST, RETENTION_EXPIRED
    }
}
