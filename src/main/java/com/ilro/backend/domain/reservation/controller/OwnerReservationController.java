package com.ilro.backend.domain.reservation.controller;

import com.ilro.backend.domain.reservation.dto.ChangeProposalRequest;
import com.ilro.backend.domain.reservation.dto.ChangeSnapshot;
import com.ilro.backend.domain.reservation.dto.CompleteCorrectionRequest;
import com.ilro.backend.domain.reservation.dto.CompleteCorrectionResponse;
import com.ilro.backend.domain.reservation.dto.ManualReservationRequest;
import com.ilro.backend.domain.reservation.dto.ManualReservationResponse;
import com.ilro.backend.domain.reservation.dto.OwnerChangeRequestsResponse;
import com.ilro.backend.domain.reservation.dto.OwnerScheduleResponse;
import com.ilro.backend.domain.reservation.dto.ReservationCancelRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCancelResponse;
import com.ilro.backend.domain.reservation.dto.ReservationCompleteRequest;
import com.ilro.backend.domain.reservation.dto.ReservationCompleteResponse;
import com.ilro.backend.domain.reservation.dto.WorkNoteRequest;
import com.ilro.backend.domain.reservation.dto.WorkNoteResponse;
import com.ilro.backend.domain.reservation.entity.ReservationChangeStatus;
import com.ilro.backend.domain.reservation.service.ManualReservationService;
import com.ilro.backend.domain.reservation.service.OwnerScheduleService;
import com.ilro.backend.domain.reservation.service.ReservationCancelService;
import com.ilro.backend.domain.reservation.service.ReservationChangeService;
import com.ilro.backend.domain.reservation.service.ReservationCompleteService;
import com.ilro.backend.domain.reservation.service.WorkNoteService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import com.ilro.backend.global.common.util.RequestFingerprint;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

// 사장님 일정·예약 (명세 4.6·5.3·5.4, BE-12·BE-13) - 업체는 토큰으로만 정해져 다른 업체 예약에 닿을 수 없음 (남의 예약은 404)
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerReservationController {

    private final OwnerScheduleService scheduleService;
    private final ManualReservationService manualReservationService;
    private final ReservationChangeService changeService;
    private final ReservationCancelService cancelService;
    private final ReservationCompleteService completeService;
    private final WorkNoteService workNoteService;

    // B-17·B-28 기간별 예약·차단 시간·이동/작업/여유시간 - from~to(yyyy-MM-dd, 둘 다 포함, 31일까지)
    @GetMapping("/schedule")
    public ApiResponse<OwnerScheduleResponse> schedule(
            AuthPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.onSuccess(scheduleService.schedule(principal.ownerId(), from, to));
    }

    // B-18 수동 예약 - 헤더 Idempotency-Key(UUID) 필수, 새로 저장하면 201, 같은 요청을 다시 받으면 처음 결과를 200
    // 영업시간 밖은 409 SLOT_UNAVAILABLE(OUTSIDE_BUSINESS_HOURS) - acknowledgeOutsideHours: true로 다시 보내면 저장
    @PostMapping("/reservations")
    public ResponseEntity<ApiResponse<ManualReservationResponse>> createManual(
            AuthPrincipal principal,
            @RequestHeader(name = RequestFingerprint.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ManualReservationRequest request) {
        ManualReservationService.Outcome outcome =
                manualReservationService.create(principal.ownerId(), idempotencyKey, request);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.onSuccess(outcome.response()));
    }

    // B-19·B-21 새 방문일시 제안 - changeId가 있으면 고객 요청에 답, 없으면 업체 사유 변경
    @PostMapping("/reservations/{reservationId}/proposals")
    public ApiResponse<ChangeSnapshot> propose(AuthPrincipal principal, @PathVariable Long reservationId,
                                               @Valid @RequestBody ChangeProposalRequest request) {
        return ApiResponse.onSuccess(changeService.propose(principal.ownerId(), reservationId, request));
    }

    // B-20 예약 취소 - reason 필수, 즉시 반영 (명세 5.4)
    @PostMapping("/reservations/{reservationId}/cancel")
    public ApiResponse<ReservationCancelResponse> cancel(AuthPrincipal principal, @PathVariable Long reservationId,
                                                         @Valid @RequestBody ReservationCancelRequest request) {
        return ApiResponse.onSuccess(cancelService.cancelByOwner(principal.ownerId(), reservationId, request));
    }

    // B-25 작업 완료 - 방문 시작 시각부터, 진행 중 변경 요청은 함께 닫음
    @PostMapping("/reservations/{reservationId}/complete")
    public ApiResponse<ReservationCompleteResponse> complete(AuthPrincipal principal, @PathVariable Long reservationId,
                                                             @Valid @RequestBody ReservationCompleteRequest request) {
        return ApiResponse.onSuccess(completeService.complete(principal.ownerId(), reservationId, request));
    }

    // B-25 실수로 한 완료 정정 - reason 필수, 확정으로 되돌리고 이력에 남김
    @PostMapping("/reservations/{reservationId}/complete-correction")
    public ApiResponse<CompleteCorrectionResponse> correctCompletion(
            AuthPrincipal principal, @PathVariable Long reservationId,
            @Valid @RequestBody CompleteCorrectionRequest request) {
        return ApiResponse.onSuccess(completeService.correct(principal.ownerId(), reservationId, request));
    }

    // B-24 작업 메모 - 없으면 빈 내용
    @GetMapping("/reservations/{reservationId}/work-note")
    public ApiResponse<WorkNoteResponse> workNote(AuthPrincipal principal, @PathVariable Long reservationId) {
        return ApiResponse.onSuccess(workNoteService.get(principal.ownerId(), reservationId));
    }

    // B-24 작업 메모 저장 - 메모 전체를 바꿈, 빈 문자열이면 비움
    @PutMapping("/reservations/{reservationId}/work-note")
    public ApiResponse<WorkNoteResponse> saveWorkNote(AuthPrincipal principal, @PathVariable Long reservationId,
                                                      @Valid @RequestBody WorkNoteRequest request) {
        return ApiResponse.onSuccess(workNoteService.save(principal.ownerId(), reservationId, request));
    }

    // B-21 고객 변경 요청·제안 대기 목록 - status 없으면 REQUESTED·PROPOSED (status=REQUESTED,PROPOSED처럼 여럿)
    @GetMapping("/change-requests")
    public ApiResponse<OwnerChangeRequestsResponse> changeRequests(
            AuthPrincipal principal, @RequestParam(required = false) List<ReservationChangeStatus> status) {
        return ApiResponse.onSuccess(changeService.list(principal.ownerId(), status));
    }

    // B-21 요청 닫기 - 기존 예약 유지 (CLOSED, OWNER_CLOSED)
    @PostMapping("/change-requests/{changeId}/close")
    public ApiResponse<ChangeSnapshot> closeChange(AuthPrincipal principal, @PathVariable Long changeId) {
        return ApiResponse.onSuccess(changeService.close(principal.ownerId(), changeId));
    }
}
