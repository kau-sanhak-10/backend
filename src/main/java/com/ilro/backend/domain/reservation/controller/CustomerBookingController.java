package com.ilro.backend.domain.reservation.controller;

import com.ilro.backend.domain.reservation.dto.AvailableSlotsResponse;
import com.ilro.backend.domain.reservation.dto.ReservationConfirmRequest;
import com.ilro.backend.domain.reservation.dto.ReservationConfirmResponse;
import com.ilro.backend.domain.reservation.service.AvailableSlotService;
import com.ilro.backend.domain.reservation.service.ReservationConfirmService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import com.ilro.backend.global.common.util.RequestFingerprint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ReservationConfirmService reservationConfirmService;

    // C-12 방문 가능 시간 후보 - from(기본 오늘, yyyy-MM-dd)부터 days일(기본 3, 최대 14)
    // 자동 예약 조건이 안 맞으면 422 + 문의는 사장님 확인 (명세 3.5)
    // 지도 일시 장애면 503 MAP_UNAVAILABLE - 문의는 그대로, 잠시 후 다시 부르면 됨
    @GetMapping("/available-slots")
    public ApiResponse<AvailableSlotsResponse> availableSlots(
            AuthPrincipal principal, @PathVariable Long inquiryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "3") @Min(1) @Max(14) int days) {
        return ApiResponse.onSuccess(availableSlotService.availableSlots(principal, inquiryId, from, days));
    }

    // C-13 예약 최종 확정 - 헤더 Idempotency-Key(UUID) 필수, 새로 저장하면 201, 같은 요청을 다시 받으면 처음 결과를 200
    // 헤더가 없거나 UUID가 아니면 400, 같은 키로 다른 시각을 보내면 409 IDEMPOTENCY_KEY_REUSED
    // 번호 인증 전 403 PHONE_NOT_VERIFIED, 그사이 시간이 막히면 409 SLOT_UNAVAILABLE, 자동 예약 조건이 안 맞으면 422 + 사장님 확인
    @PostMapping("/reservations")
    public ResponseEntity<ApiResponse<ReservationConfirmResponse>> confirm(
            AuthPrincipal principal, @PathVariable Long inquiryId,
            @RequestHeader(name = RequestFingerprint.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ReservationConfirmRequest request) {
        ReservationConfirmService.Outcome outcome =
                reservationConfirmService.confirm(principal, inquiryId, idempotencyKey, request);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.onSuccess(outcome.response()));
    }
}
