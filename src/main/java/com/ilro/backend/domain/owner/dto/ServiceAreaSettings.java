package com.ilro.backend.domain.owner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

// 서비스 지역 (B-06) - 요청·응답 같은 모양, 저장은 전체 교체
// regionCode·regionName은 지역 검색(BE-15) 결과를 그대로 보냄
public record ServiceAreaSettings(
        @NotNull(message = "필수 값입니다") @Size(max = 50, message = "서비스 지역은 50개까지입니다")
        List<@Valid @NotNull(message = "필수 값입니다") ServiceArea> serviceAreas) {

    public record ServiceArea(
            @NotNull(message = "필수 값입니다") @Pattern(regexp = "\\d{5,10}", message = "숫자 지역 코드여야 합니다")
            String regionCode,
            @NotBlank(message = "필수 값입니다") @Size(max = 50, message = "50자 이하여야 합니다")
            String regionName) {
    }
}
