package com.ilro.backend.domain.reservation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// 사장님 수동 예약 (B-18, 명세 4.6, BE-12) - 전화 등으로 받은 예약을 직접 등록
// 필수: 고객 휴대전화·주소와 좌표(주소 검색 결과)·작업 유형·시작 시각 (분 단위) - 빠지면 저장하지 않음
// acknowledgeOutsideHours: 영업시간 밖 경고를 확인함 - 영업시간만 예외, 점심·차단·하루 한도·겹침·이동은 예외 없음
public record ManualReservationRequest(@NotBlank @Size(max = 20) String customerPhone,
                                       @Size(max = 50) String customerName,
                                       @NotBlank @Size(max = 255) String address,
                                       @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                                       @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
                                       @NotNull Long workTypeId,
                                       @NotNull OffsetDateTime startAt,
                                       Boolean acknowledgeOutsideHours) {

    // 빼면 false
    public ManualReservationRequest {
        acknowledgeOutsideHours = Boolean.TRUE.equals(acknowledgeOutsideHours);
    }
}
