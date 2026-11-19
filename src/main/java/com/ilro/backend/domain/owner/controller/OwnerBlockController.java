package com.ilro.backend.domain.owner.controller;

import com.ilro.backend.domain.owner.dto.OwnerBlockRequest;
import com.ilro.backend.domain.owner.dto.OwnerBlockResponse;
import com.ilro.backend.domain.owner.service.OwnerBlockService;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

// 차단 시간 (B-22, 명세 4.6, BE-12) - 확정 예약과 겹치면 409 BLOCK_CONFLICTS_RESERVATION,
// acknowledgeConflicts: true로 다시 보내면 저장 (겹친 예약은 그대로)
@RestController
@RequestMapping("/api/v1/owner/blocks")
@RequiredArgsConstructor
public class OwnerBlockController {

    private final OwnerBlockService blockService;

    // from~to(yyyy-MM-dd, 둘 다 포함, 62일까지)와 겹치는 차단 시간
    @GetMapping
    public ApiResponse<List<OwnerBlockResponse>> list(
            AuthPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.onSuccess(blockService.list(principal.ownerId(), from, to));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OwnerBlockResponse>> create(AuthPrincipal principal,
                                                                  @Valid @RequestBody OwnerBlockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.onSuccess(blockService.create(principal.ownerId(), request)));
    }

    @PutMapping("/{blockId}")
    public ApiResponse<OwnerBlockResponse> update(AuthPrincipal principal, @PathVariable Long blockId,
                                                  @Valid @RequestBody OwnerBlockRequest request) {
        return ApiResponse.onSuccess(blockService.update(principal.ownerId(), blockId, request));
    }

    @DeleteMapping("/{blockId}")
    public ApiResponse<Void> delete(AuthPrincipal principal, @PathVariable Long blockId) {
        blockService.delete(principal.ownerId(), blockId);
        return ApiResponse.onSuccess();
    }
}
