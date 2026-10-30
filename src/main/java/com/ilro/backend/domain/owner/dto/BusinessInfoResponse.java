package com.ilro.backend.domain.owner.dto;

import com.ilro.backend.domain.owner.entity.Owner;

import java.math.BigDecimal;

// 업체 정보 (B-05)
public record BusinessInfoResponse(String businessName, String contactPhone, String address, BigDecimal lat,
                                   BigDecimal lng, String customerNotice, String serviceDescription) {

    public static BusinessInfoResponse from(Owner owner) {
        return new BusinessInfoResponse(owner.getBusinessName(), owner.getContactPhone(), owner.getAddress(),
                owner.getLat(), owner.getLng(), owner.getCustomerNotice(), owner.getServiceDescription());
    }
}
