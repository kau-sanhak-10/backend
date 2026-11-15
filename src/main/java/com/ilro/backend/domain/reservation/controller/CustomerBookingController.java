package com.ilro.backend.domain.reservation.controller;

import com.ilro.backend.domain.reservation.dto.AvailableSlotsResponse;
import com.ilro.backend.domain.reservation.service.AvailableSlotService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// 고객 방문 예약 (명세 4.2·5.2, BE-09~) - 문의 토큰(inq_)으로 그 문의에만 접근
@RestController
@RequestMapping("/api/v1/customer/inquiries/{inquiryId}")
@RequiredArgsConstructor
public class CustomerBookingController {

    private final AvailableSlotService availableSlotService;

    // C-12 방문 가능 시간 후보 - from(기본 오늘, yyyy-MM-dd)부터 days일(기본 3, 최대 14)
    // 자동 예약 조건이 안 맞으면 422 + 문의는 사장님 확인 (명세 3.5)
    @GetMapping("/available-slots")
    public ApiResponse<AvailableSlotsResponse> availableSlots(
            AuthPrincipal principal, @PathVariable Long inquiryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "3") @Min(1) @Max(14) int days) {
        return ApiResponse.onSuccess(availableSlotService.availableSlots(principal, inquiryId, from, days));
    }
}
