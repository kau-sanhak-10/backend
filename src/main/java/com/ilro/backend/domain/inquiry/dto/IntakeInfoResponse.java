package com.ilro.backend.domain.inquiry.dto;

import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.owner.entity.WorkCategory;

import java.util.List;

// 접수 링크로 본 업체 (C-01) - 접수 중지여도 업체 안내는 보여 주고 intakeAvailable로 구분
// contactPhone은 대표 연락처, 없으면 사장님 번호
public record IntakeInfoResponse(String businessName, String contactPhone, String customerNotice,
                                 String serviceDescription, List<WorkCategory> workCategories,
                                 boolean intakeAvailable, String termsVersion, List<ConsentItem> consents) {

    public record ConsentItem(ConsentType type, boolean required) {
    }
}
