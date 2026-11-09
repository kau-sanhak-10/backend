package com.ilro.backend.domain.inquiry.controller;

import com.ilro.backend.domain.inquiry.dto.PhotoStatusResponse;
import com.ilro.backend.domain.inquiry.dto.PhotoUploadResponse;
import com.ilro.backend.domain.inquiry.dto.PhotoUrlsResponse;
import com.ilro.backend.domain.inquiry.dto.StartPhotoUploadRequest;
import com.ilro.backend.domain.inquiry.service.CustomerPhotoService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 고객 사진 (C-05, BE-06) - 문의 토큰(inq_)으로 그 문의 사진에만
// 순서: 업로드 시작 → 받은 uploadUrl로 S3에 PUT → complete → 메시지 photoIds로 보내기
@RestController
@RequestMapping("/api/v1/customer/inquiries/{inquiryId}/photos")
@RequiredArgsConstructor
public class CustomerPhotoController {

    private final CustomerPhotoService photoService;

    // 업로드 시작 - 사진 행을 새로 만들므로 201
    @PostMapping
    public ResponseEntity<ApiResponse<PhotoUploadResponse>> startUpload(
            AuthPrincipal principal, @PathVariable Long inquiryId, @Valid @RequestBody StartPhotoUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.onSuccess(photoService.startUpload(principal, inquiryId, request)));
    }

    // 업로드 완료 확인 - DONE이면 메시지에 붙일 수 있음, FAILED면 업로드 시작부터 다시
    @PostMapping("/{photoId}/complete")
    public ApiResponse<PhotoStatusResponse> complete(AuthPrincipal principal, @PathVariable Long inquiryId,
                                                     @PathVariable Long photoId) {
        return ApiResponse.onSuccess(photoService.complete(principal, inquiryId, photoId));
    }

    // 사진 보기 주소 - ?ids=1,2 (한 번에 10장까지)
    @GetMapping
    public ApiResponse<PhotoUrlsResponse> viewUrls(
            AuthPrincipal principal, @PathVariable Long inquiryId,
            @RequestParam @NotEmpty(message = "필수 값입니다") @Size(max = 10, message = "한 번에 10장까지입니다")
            List<@NotNull(message = "필수 값입니다") Long> ids) {
        return ApiResponse.onSuccess(photoService.viewUrls(principal, inquiryId, ids));
    }
}