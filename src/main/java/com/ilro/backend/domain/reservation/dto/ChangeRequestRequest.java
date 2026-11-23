package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// 고객 변경 요청 (C-15, 명세 5.3 1단계) - 희망 날짜·시간대·사유는 requestNote에만, 시간은 사장님이 제안
// addressChange: 방문 주소를 바꾸고 싶을 때 (주소 검색 결과의 좌표 포함) - 사장님이 제안할 때 지역·이동시간과 함께 확인
// requestNote와 addressChange 중 하나는 있어야 함, baseVersion은 화면이 본 예약 버전
public record ChangeRequestRequest(@Size(max = 255) String requestNote, @Valid AddressChange addressChange,
                                   @NotNull Integer baseVersion) {

    public record AddressChange(@NotBlank @Size(max = 200) String address, @Size(max = 50) String addressDetail,
                                @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                                @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng) {

        // 예약에는 주소 한 칸뿐이라 상세 주소를 붙여 저장
        public String fullAddress() {
            String detail = addressDetail == null ? "" : addressDetail.trim();
            return detail.isEmpty() ? address.trim() : address.trim() + " " + detail;
        }
    }
}
