package com.ilro.backend.domain.reservation.controller;

import com.ilro.backend.domain.reservation.dto.CustomerReservationDetailResponse;
import com.ilro.backend.domain.reservation.dto.CustomerReservationsResponse;
import com.ilro.backend.domain.reservation.service.CustomerReservationService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 고객 예약 조회 (명세 4.3, BE-11) - 변경·취소 요청은 BE-16
@RestController
@RequestMapping("/api/v1/customer/reservations")
@RequiredArgsConstructor
public class CustomerReservationController {

    private final CustomerReservationService customerReservationService;

    // C-14 내 예약 목록 - 예약 조회 인증(RESERVATION_LOOKUP)으로 받은 고객 토큰만, 그 번호·그 업체의 예약
    @GetMapping
    public ApiResponse<CustomerReservationsResponse> list(AuthPrincipal principal) {
        return ApiResponse.onSuccess(customerReservationService.list(principal));
    }

    // C-14·C-17 예약 상세 + 진행 중 변경 - 문의 토큰이면 그 문의의 예약만, 남의 예약은 404
    @GetMapping("/{reservationId}")
    public ApiResponse<CustomerReservationDetailResponse> detail(AuthPrincipal principal,
                                                                 @PathVariable Long reservationId) {
        return ApiResponse.onSuccess(customerReservationService.detail(principal, reservationId));
    }
}
