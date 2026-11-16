package com.ilro.backend.domain.address.controller;

import com.ilro.backend.domain.address.dto.AddressSearchResponse;
import com.ilro.backend.domain.address.dto.RegionSearchResponse;
import com.ilro.backend.domain.address.service.AddressSearchService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 주소·지역 검색 (BE-15) - 고객(문의 토큰)과 사장님(업체 토큰)이 같은 검색을 씀
// 결과 없음은 200 + 빈 목록, 검색어가 비었거나 너무 길면 400 INVALID_INPUT, 지도 장애는 503 MAP_UNAVAILABLE
@RestController
@RequiredArgsConstructor
public class AddressSearchController {

    private final AddressSearchService addressSearchService;

    // C-06 고객 방문 주소 검색
    @GetMapping("/api/v1/customer/inquiries/{inquiryId}/addresses")
    public ApiResponse<AddressSearchResponse> searchCustomerAddress(
            AuthPrincipal principal, @PathVariable Long inquiryId,
            @RequestParam @NotBlank(message = "검색어를 입력해 주세요") @Size(max = 100, message = "100자 이하여야 합니다")
            String query) {
        return ApiResponse.onSuccess(new AddressSearchResponse(
                addressSearchService.searchAddressForInquiry(principal, inquiryId, query)));
    }

    // B-05 업체 출발지 주소 검색
    @GetMapping("/api/v1/owner/addresses")
    public ApiResponse<AddressSearchResponse> searchOwnerAddress(
            @RequestParam @NotBlank(message = "검색어를 입력해 주세요") @Size(max = 100, message = "100자 이하여야 합니다")
            String query) {
        return ApiResponse.onSuccess(new AddressSearchResponse(addressSearchService.searchAddress(query)));
    }

    // B-06 서비스 지역 검색 (시군구)
    @GetMapping("/api/v1/owner/regions")
    public ApiResponse<RegionSearchResponse> searchRegion(
            @RequestParam @NotBlank(message = "검색어를 입력해 주세요") @Size(max = 100, message = "100자 이하여야 합니다")
            String query) {
        return ApiResponse.onSuccess(new RegionSearchResponse(addressSearchService.searchRegion(query)));
    }
}