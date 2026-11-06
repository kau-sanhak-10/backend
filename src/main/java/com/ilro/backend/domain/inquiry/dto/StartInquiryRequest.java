package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.ConsentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// 상담 시작 (C-01·C-06) - 필수 개인정보 동의(PRIVACY_REQUIRED)가 있어야 문의가 생김, 사진 동의(PHOTO)는 선택
public record StartInquiryRequest(
        @NotNull(message = "필수 값입니다") @Size(max = 2, message = "동의는 종류별로 한 번씩만 보내 주세요")
        List<@Valid @NotNull(message = "필수 값입니다") ConsentItem> consents) {

    public record ConsentItem(
            @NotNull(message = "필수 값입니다")
            ConsentType type,

            // 화면이 보여 준 약관 버전 - 지금 버전과 다르면 다시 동의
            @NotBlank(message = "필수 값입니다") @Size(max = 20, message = "20자 이하여야 합니다")
            String termsVersion) {
    }
}
