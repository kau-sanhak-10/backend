package com.ilro.backend.domain.owner.controller;

import com.ilro.backend.domain.owner.dto.AutoBookingRequest;
import com.ilro.backend.domain.owner.dto.AutoBookingResponse;
import com.ilro.backend.domain.owner.dto.BookingSettingsRequest;
import com.ilro.backend.domain.owner.dto.BookingSettingsResponse;
import com.ilro.backend.domain.owner.dto.BusinessInfoRequest;
import com.ilro.backend.domain.owner.dto.BusinessInfoResponse;
import com.ilro.backend.domain.owner.dto.IntakeRequest;
import com.ilro.backend.domain.owner.dto.IntakeResponse;
import com.ilro.backend.domain.owner.dto.ServiceAreaSettings;
import com.ilro.backend.domain.owner.dto.WorkTypeSettings;
import com.ilro.backend.domain.owner.service.OwnerBookingSettingsService;
import com.ilro.backend.domain.owner.service.OwnerBusinessService;
import com.ilro.backend.domain.owner.service.OwnerServiceAreaService;
import com.ilro.backend.domain.owner.service.OwnerWorkTypeService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 업체 정보·운영 규칙 (명세 4.4, BE-03) - 업체는 토큰으로만 정해져 다른 업체 설정에 닿을 수 없음
@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerSettingsController {

    private final OwnerBusinessService businessService;
    private final OwnerWorkTypeService workTypeService;
    private final OwnerServiceAreaService serviceAreaService;
    private final OwnerBookingSettingsService bookingSettingsService;

    // B-05 상호·대표 연락처·출발지·고객 안내 문구·제공 서비스 문구
    @GetMapping("/business")
    public ApiResponse<BusinessInfoResponse> getBusiness(AuthPrincipal principal) {
        return ApiResponse.onSuccess(businessService.getBusiness(principal.ownerId()));
    }

    @PutMapping("/business")
    public ApiResponse<BusinessInfoResponse> updateBusiness(AuthPrincipal principal,
                                                            @Valid @RequestBody BusinessInfoRequest request) {
        return ApiResponse.onSuccess(businessService.updateBusiness(principal.ownerId(), request));
    }

    // B-05·B-08 작업 종류별 제공 여부·소요시간(기본 60분)·자동 예약 허용
    @GetMapping("/work-types")
    public ApiResponse<WorkTypeSettings> getWorkTypes(AuthPrincipal principal) {
        return ApiResponse.onSuccess(workTypeService.getWorkTypes(principal.ownerId()));
    }

    @PutMapping("/work-types")
    public ApiResponse<WorkTypeSettings> updateWorkTypes(AuthPrincipal principal,
                                                         @Valid @RequestBody WorkTypeSettings request) {
        return ApiResponse.onSuccess(workTypeService.updateWorkTypes(principal.ownerId(), request));
    }

    // B-06 서비스 지역 (시군구 코드)
    @GetMapping("/service-areas")
    public ApiResponse<ServiceAreaSettings> getServiceAreas(AuthPrincipal principal) {
        return ApiResponse.onSuccess(serviceAreaService.getServiceAreas(principal.ownerId()));
    }

    @PutMapping("/service-areas")
    public ApiResponse<ServiceAreaSettings> updateServiceAreas(AuthPrincipal principal,
                                                               @Valid @RequestBody ServiceAreaSettings request) {
        return ApiResponse.onSuccess(serviceAreaService.updateServiceAreas(principal.ownerId(), request));
    }

    // B-07·B-08 요일별 영업시간·점심·하루 한도·여유시간·이동수단
    @GetMapping("/booking-settings")
    public ApiResponse<BookingSettingsResponse> getBookingSettings(AuthPrincipal principal) {
        return ApiResponse.onSuccess(bookingSettingsService.getBookingSettings(principal.ownerId()));
    }

    @PutMapping("/booking-settings")
    public ApiResponse<BookingSettingsResponse> updateBookingSettings(AuthPrincipal principal,
                                                                      @Valid @RequestBody BookingSettingsRequest request) {
        return ApiResponse.onSuccess(bookingSettingsService.updateBookingSettings(principal.ownerId(), request));
    }

    // B-07 자동 예약 켜기·끄기 - 켤 때 필수 설정이 빠졌으면 422 AUTO_BOOKING_SETUP_INCOMPLETE
    @PatchMapping("/booking-settings/auto-booking")
    public ApiResponse<AutoBookingResponse> changeAutoBooking(AuthPrincipal principal,
                                                              @Valid @RequestBody AutoBookingRequest request) {
        return ApiResponse.onSuccess(bookingSettingsService.changeAutoBooking(principal.ownerId(), request.enabled()));
    }

    // B-09 신규 접수 중지·재개
    @PatchMapping("/intake")
    public ApiResponse<IntakeResponse> changeIntake(AuthPrincipal principal, @Valid @RequestBody IntakeRequest request) {
        return ApiResponse.onSuccess(businessService.changeIntake(principal.ownerId(), request.intakePaused()));
    }
}
