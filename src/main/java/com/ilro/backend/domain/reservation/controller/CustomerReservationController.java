package com.ilro.backend.domain.reservation.controller;

import com.ilro.backend.domain.reservation.dto.ChangeAcceptRequest;
import com.ilro.backend.domain.reservation.dto.ChangeAcceptResponse;
import com.ilro.backend.domain.reservation.dto.ChangeHistoryResponse;
import com.ilro.backend.domain.reservation.dto.ChangeRequestRequest;
import com.ilro.backend.domain.reservation.dto.ChangeSnapshot;
import com.ilro.backend.domain.reservation.dto.CustomerReservationDetailResponse;
import com.ilro.backend.domain.reservation.dto.CustomerReservationsResponse;
import com.ilro.backend.domain.reservation.dto.ReservationCancelRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCancelResponse;
import com.ilro.backend.domain.reservation.service.CustomerReservationService;
import com.ilro.backend.domain.reservation.service.ReservationCancelService;
import com.ilro.backend.domain.reservation.service.ReservationChangeService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 고객 예약 조회·변경·취소 (명세 4.3·5.3·5.4, BE-11·BE-12·BE-16) - 문의 토큰이면 그 문의의 예약만, 남의 예약은 404
@RestController
@RequestMapping("/api/v1/customer/reservations")
@RequiredArgsConstructor
public class CustomerReservationController {

    private final CustomerReservationService customerReservationService;
    private final ReservationChangeService changeService;
    private final ReservationCancelService cancelService;

    // C-14 내 예약 목록 - 예약 조회 인증(RESERVATION_LOOKUP)으로 받은 고객 토큰만, 그 번호·그 업체의 예약
    @GetMapping
    public ApiResponse<CustomerReservationsResponse> list(AuthPrincipal principal) {
        return ApiResponse.onSuccess(customerReservationService.list(principal));
    }

    // C-14·C-17 예약 상세 + 진행 중 변경
    @GetMapping("/{reservationId}")
    public ApiResponse<CustomerReservationDetailResponse> detail(AuthPrincipal principal,
                                                                 @PathVariable Long reservationId) {
        return ApiResponse.onSuccess(customerReservationService.detail(principal, reservationId));
    }

    // C-17 변경 요청·제안 이력 - 기존·제안 시간·주소와 기한, 끝난 변경도 포함 (최근 순, BE-16)
    @GetMapping("/{reservationId}/changes")
    public ApiResponse<ChangeHistoryResponse> changes(AuthPrincipal principal, @PathVariable Long reservationId) {
        return ApiResponse.onSuccess(customerReservationService.changes(principal, reservationId));
    }

    // C-15 변경 요청 - 희망 일시·사유는 requestNote에, 주소 변경은 addressChange (예약은 아직 그대로)
    @PostMapping("/{reservationId}/change-requests")
    public ResponseEntity<ApiResponse<ChangeSnapshot>> requestChange(
            AuthPrincipal principal, @PathVariable Long reservationId,
            @Valid @RequestBody ChangeRequestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.onSuccess(changeService.request(principal, reservationId, request)));
    }

    // C-17 제안 수락 → 최종 검사 → 변경 확정 (실패면 409 + 기존 예약 그대로)
    @PostMapping("/{reservationId}/changes/{changeId}/accept")
    public ApiResponse<ChangeAcceptResponse> accept(AuthPrincipal principal, @PathVariable Long reservationId,
                                                    @PathVariable Long changeId,
                                                    @Valid @RequestBody ChangeAcceptRequest request) {
        return ApiResponse.onSuccess(changeService.accept(principal, reservationId, changeId, request));
    }

    // C-17 제안 거절 - 기존 예약 그대로, 고객은 새 요청을 만들 수 있음
    @PostMapping("/{reservationId}/changes/{changeId}/reject")
    public ApiResponse<ChangeSnapshot> reject(AuthPrincipal principal, @PathVariable Long reservationId,
                                              @PathVariable Long changeId) {
        return ApiResponse.onSuccess(changeService.reject(principal, reservationId, changeId));
    }

    // C-16 즉시 취소 (명세 5.4)
    @PostMapping("/{reservationId}/cancel")
    public ApiResponse<ReservationCancelResponse> cancel(AuthPrincipal principal, @PathVariable Long reservationId,
                                                         @Valid @RequestBody ReservationCancelRequest request) {
        return ApiResponse.onSuccess(cancelService.cancelByCustomer(principal, reservationId, request));
    }
}
