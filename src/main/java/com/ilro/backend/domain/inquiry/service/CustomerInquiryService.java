package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import com.ilro.backend.domain.ai.repository.AiDecisionRepository;
import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.customer.repository.CustomerRepository;
import com.ilro.backend.domain.inquiry.dto.InquiryDetailResponse;
import com.ilro.backend.domain.inquiry.dto.VisitInfoRequest;
import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.ConsentRepository;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// 고객 문의 조회·방문정보 저장 (C-03·C-06·C-09, 명세 4.2)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerInquiryService {

    // inquiry.lat·lng NUMERIC(10,7)
    private static final int COORDINATE_SCALE = 7;

    private final InquiryAccess inquiryAccess;
    private final CustomerRepository customerRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final ReservationRepository reservationRepository;
    private final ConsentRepository consentRepository;
    private final AiDecisionRepository aiDecisionRepository;

    public InquiryDetailResponse getInquiry(AuthPrincipal principal, Long inquiryId) {
        Inquiry inquiry = inquiryAccess.find(principal, inquiryId);
        InquiryDetailResponse response = detail(inquiry);
        AccessLog.record(principal, "VIEW", "inquiry", inquiryId);
        return response;
    }

    // 고객은 업체별로 번호 하나 - 같은 번호면 이전 문의의 고객과 이어짐
    @Transactional
    public InquiryDetailResponse updateVisitInfo(AuthPrincipal principal, Long inquiryId, VisitInfoRequest request) {
        String phone = PhoneNumbers.normalize(request.phone());
        if (!PhoneNumbers.isMobile(phone)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("phone", "휴대전화 번호를 입력해 주세요")));
        }

        Inquiry inquiry = inquiryAccess.lock(principal, inquiryId);
        inquiryAccess.checkWritable(inquiry);
        inquiryAccess.checkVersion(inquiry, request.version());
        OwnerWorkType workType = request.workCategory() == null
                ? inquiry.getWorkType()
                : findOfferedWorkType(inquiry, request.workCategory());
        String address = request.address().strip();
        // DB 칸(NUMERIC(10,7))과 같은 자릿수로 맞춰 저장·비교 - 지도 API가 더 긴 소수를 줘도 같은 위치는 같은 값
        BigDecimal lat = request.lat().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal lng = request.lng().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        checkReservationUnaffected(inquiry, phone, address, lat, lng, workType);

        int versionBefore = inquiry.getVersion();
        boolean phoneVerified = inquiry.getPhoneVerifiedAt() != null && inquiry.getCustomer() != null
                && inquiry.getCustomer().getPhone().equals(phone);
        Long ownerId = inquiry.getOwner().getId();
        customerRepository.insertIfAbsent(ownerId, phone);
        Customer customer = customerRepository.findByOwnerIdAndPhone(ownerId, phone).orElseThrow();
        // 번호 인증 전에는 이 문의만 쓰는 고객의 이름만 바꿈 - 남의 번호를 넣어 기존 고객 이름을 바꾸지 못하게
        if (phoneVerified || customerRepository.isUsedOnlyBy(customer.getId(), inquiry.getId())) {
            customer.changeName(request.name().strip());
        }
        inquiry.updateVisitInfo(customer, address, strip(request.addressDetail()), lat, lng,
                strip(request.accessNote()));
        inquiry.changeWorkType(workType);
        inquiryAccess.markInfoChanged(inquiry, versionBefore);

        InquiryDetailResponse response = detail(inquiry);
        AccessLog.record(principal, "UPDATE", "inquiry", inquiryId);
        return response;
    }

    // 고객 화면의 작업 목록은 업체가 제공하는 것만 보여 주므로 그 밖의 값은 입력 오류
    private OwnerWorkType findOfferedWorkType(Inquiry inquiry, WorkCategory workCategory) {
        return workTypeRepository.findByOwnerId(inquiry.getOwner().getId()).stream()
                .filter(workType -> workType.getWorkCategory() == workCategory && workType.isActive())
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT,
                        List.of(new FieldErrorResponse("workCategory", "이 업체가 제공하지 않는 작업이에요"))));
    }

    // 진행 중인 예약이 있으면 방문 주소·작업(소요시간)·연락처(예약의 고객)를 바꾸지 않고 예약 변경 절차로 (BE-16)
    // 지금 문의 값과 비교 - 이름·상세 주소·출입 안내만 바뀌면 그대로 저장
    private void checkReservationUnaffected(Inquiry inquiry, String phone, String address, BigDecimal lat,
                                            BigDecimal lng, OwnerWorkType workType) {
        Customer current = inquiry.getCustomer();
        boolean affectsVisit = !Objects.equals(inquiry.getAddress(), address)
                || !sameNumber(inquiry.getLat(), lat) || !sameNumber(inquiry.getLng(), lng)
                || !Objects.equals(idOf(inquiry.getWorkType()), idOf(workType))
                || (current != null && !current.getPhone().equals(phone));
        if (!affectsVisit) {
            return;
        }
        reservationRepository.findByInquiryIdAndStatusNot(inquiry.getId(), ReservationStatus.CANCELED)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.REQUESTED
                        || reservation.getStatus() == ReservationStatus.CONFIRMED)
                .ifPresent(reservation -> {
                    throw new BusinessException(InquiryErrorCode.RESERVATION_CHANGE_REQUIRED,
                            Collections.singletonMap("reservationId", reservation.getId()));
                });
    }

    // 고객 이름은 번호를 인증했거나 이 문의만 쓰는 고객일 때만 보여 줌 (다른 사람이 같은 번호로 넣은 이름을 숨김)
    private InquiryDetailResponse detail(Inquiry inquiry) {
        Customer customer = inquiry.getCustomer();
        boolean showName = customer != null && (inquiry.getPhoneVerifiedAt() != null
                || customerRepository.isUsedOnlyBy(customer.getId(), inquiry.getId()));
        Reservation reservation = reservationRepository
                .findByInquiryIdAndStatusNot(inquiry.getId(), ReservationStatus.CANCELED)
                .orElse(null);
        AiAction nextAction = inquiry.getHandlerMode() != HandlerMode.AI ? null : aiDecisionRepository
                .findFirstByInquiryIdAndStageAndStatusOrderByIdDesc(inquiry.getId(), AiStage.DECIDE,
                        AiDecisionStatus.SUCCESS)
                .map(AiDecision::getAction)
                .orElse(null);
        List<ConsentType> consents = consentRepository.findByInquiryIdOrderByIdAsc(inquiry.getId()).stream()
                .map(Consent::getConsentType)
                .distinct()
                .toList();
        return InquiryDetailResponse.of(inquiry, showName, nextAction, consents, reservation);
    }

    // 37.5 와 37.5000000 은 같은 좌표
    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Long idOf(OwnerWorkType workType) {
        return workType == null ? null : workType.getId();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
