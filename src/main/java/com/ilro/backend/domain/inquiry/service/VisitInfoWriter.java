package com.ilro.backend.domain.inquiry.service;

import com.ilro.backend.domain.customer.entity.Customer;
import com.ilro.backend.domain.customer.repository.CustomerRepository;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.owner.entity.OwnerWorkType;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.domain.owner.repository.OwnerWorkTypeRepository;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// 방문정보 저장 - 고객(C-06)과 사장님(B-14)이 같은 규칙으로 저장
// 문의 행을 잠그고 상태·버전을 확인한 뒤 부름. version·AI 입력 순번은 부른 쪽이 올림
@Component
@RequiredArgsConstructor
public class VisitInfoWriter {

    // inquiry.lat·lng NUMERIC(10,7)
    private static final int COORDINATE_SCALE = 7;

    private final CustomerRepository customerRepository;
    private final OwnerWorkTypeRepository workTypeRepository;
    private final ReservationRepository reservationRepository;

    // 전체 교체 - 비울 칸은 null, workCategory가 null이면 지금 작업(AI 분류 포함)을 그대로 둠
    // fieldPrefix: 입력 오류 위치 앞에 붙임 (사장님 수정은 "visitInfo.")
    public record Input(String name, String phone, String address, String addressDetail, BigDecimal lat,
                        BigDecimal lng, String accessNote, WorkCategory workCategory, String fieldPrefix) {
    }

    // 휴대전화 번호로 맞춤 - 잠그기 전에 확인
    public static String mobilePhone(String phone, String field) {
        String normalized = PhoneNumbers.normalize(phone);
        if (!PhoneNumbers.isMobile(normalized)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse(field, "휴대전화 번호를 입력해 주세요")));
        }
        return normalized;
    }

    // phone은 mobilePhone으로 맞춘 번호
    // 고객은 업체별로 번호 하나 - 같은 번호면 이전 문의의 고객과 이어짐
    // trustedName: 사장님이 고친 이름은 그대로 저장. 고객이 보낸 이름은 번호 인증 전이면 이 문의만 쓰는 고객일 때만
    //   (남의 번호를 넣어 기존 고객 이름을 바꾸지 못하게)
    // 바뀐 내용이 있으면 true - 그대로면 부른 쪽이 version·AI 입력 순번을 올리지 않음
    public boolean write(Inquiry inquiry, Input input, boolean trustedName) {
        OwnerWorkType workType = input.workCategory() == null
                ? inquiry.getWorkType()
                : findOfferedWorkType(inquiry, input.workCategory(), input.fieldPrefix());
        String address = input.address().strip();
        // DB 칸(NUMERIC(10,7))과 같은 자릿수로 맞춰 저장·비교 - 지도 API가 더 긴 소수를 줘도 같은 위치는 같은 값
        BigDecimal lat = input.lat().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal lng = input.lng().setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        String phone = input.phone();
        checkReservationUnaffected(inquiry, phone, address, lat, lng, workType);

        boolean phoneVerified = inquiry.getPhoneVerifiedAt() != null && inquiry.getCustomer() != null
                && inquiry.getCustomer().getPhone().equals(phone);
        Long ownerId = inquiry.getOwner().getId();
        customerRepository.insertIfAbsent(ownerId, phone);
        Customer customer = customerRepository.findByOwnerIdAndPhone(ownerId, phone).orElseThrow();
        String addressDetail = strip(input.addressDetail());
        String accessNote = strip(input.accessNote());
        boolean changed = inquiry.getCustomer() == null || !inquiry.getCustomer().getId().equals(customer.getId())
                || !Objects.equals(inquiry.getAddress(), address)
                || !Objects.equals(inquiry.getAddressDetail(), addressDetail)
                || !sameNumber(inquiry.getLat(), lat) || !sameNumber(inquiry.getLng(), lng)
                || !Objects.equals(inquiry.getAccessNote(), accessNote)
                || !Objects.equals(idOf(inquiry.getWorkType()), idOf(workType));
        String name = input.name().strip();
        if ((trustedName || phoneVerified || customerRepository.isUsedOnlyBy(customer.getId(), inquiry.getId()))
                && !name.equals(customer.getName())) {
            customer.changeName(name);
            changed = true;
        }
        if (changed) {
            inquiry.updateVisitInfo(customer, address, addressDetail, lat, lng, accessNote);
            inquiry.changeWorkType(workType);
        }
        return changed;
    }

    // 화면의 작업 목록은 업체가 제공하는 것만 보여 주므로 그 밖의 값은 입력 오류
    private OwnerWorkType findOfferedWorkType(Inquiry inquiry, WorkCategory workCategory, String fieldPrefix) {
        return workTypeRepository.findByOwnerId(inquiry.getOwner().getId()).stream()
                .filter(workType -> workType.getWorkCategory() == workCategory && workType.isActive())
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT,
                        List.of(new FieldErrorResponse(fieldPrefix + "workCategory", "이 업체가 제공하지 않는 작업이에요"))));
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
