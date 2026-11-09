package com.ilro.backend.domain.inquiry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 전화 회신 요청 (C-18, 명세 5.5) - 화면은 방문정보의 이름·번호로 미리 채움
// 회신 요청에만 저장 - 고객 번호·예약 연락처·문자 인증은 바꾸지 않음
public record CallbackRequest(
        @NotBlank(message = "필수 값입니다") @Size(max = 50, message = "50자 이하여야 합니다")
        String name,

        // 회신받을 휴대전화
        @NotBlank(message = "필수 값입니다") @Size(max = 20, message = "20자 이하여야 합니다")
        String phone) {
}
