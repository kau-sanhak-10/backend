package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.domain.notification.service.NeedsCheckNotifier;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 자동 예약이 막히면 문의를 사장님 확인으로 넘기고 알림 (명세 5.2) - 고객에게 422를 돌려주기 전에 따로 저장
// (조회 요청이 오류로 끝나도 이 변경은 남아야 하므로 새 트랜잭션)
// 같은 문의·같은 이유·같은 버전은 알림 하나 - 사장님이 AI를 다시 켠 뒤 또 막히면 다시 알림
@Component
@RequiredArgsConstructor
public class BookingEscalation {

    private final InquiryRepository inquiryRepository;
    private final InquiryAccess inquiryAccess;
    private final NeedsCheckNotifier needsCheckNotifier;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void askOwner(Long inquiryId, ReservationErrorCode code) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(inquiryId).orElse(null);
        // 그 사이 사장님이 직접 응대를 시작했거나 단계가 바뀌었으면 그대로 둠
        if (inquiry == null || inquiry.getHandlerMode() != HandlerMode.AI || !inquiry.canMoveToOwnerCheck()) {
            return;
        }
        int versionBefore = inquiry.getVersion();
        inquiry.moveToOwnerCheck();
        needsCheckNotifier.notify(inquiry,
                "NEEDS_CHECK:BOOKING:" + inquiryId + ":" + code.getCode() + ":" + versionBefore);
        inquiryAccess.bumpVersion(inquiry, versionBefore);
    }
}
