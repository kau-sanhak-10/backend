package com.ilro.backend.domain.owner.dto;

// 접수 링크 (B-11·O-04) - 토큰은 업체 등록 때부터 있어서 지금은 항상 READY
public record IntakeLinkResponse(String status, String url, String businessName) {

    public static IntakeLinkResponse ready(String url, String businessName) {
        return new IntakeLinkResponse("READY", url, businessName);
    }
}