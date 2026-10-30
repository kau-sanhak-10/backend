package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.AutoBookingResponse;
import com.ilro.backend.domain.owner.dto.BookingSettingsRequest;
import com.ilro.backend.domain.owner.dto.BookingSettingsResponse;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerBusinessHour;
import com.ilro.backend.domain.owner.entity.TravelMode;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerBusinessHourRepository;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// 예약 기준 - 요일별 영업시간·점심·하루 한도·여유시간·이동수단, 자동 예약 켜기·끄기 (B-07·B-08, 명세 4.4)
// 일정을 바꾸는 쓰기라 업체 행을 잠그고 바꿈 (명세 1.7), 이미 잡힌 예약은 취소·변경하지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerBookingSettingsService {

    private final OwnerRepository ownerRepository;
    private final OwnerBusinessHourRepository businessHourRepository;
    private final AutoBookingSetup autoBookingSetup;

    public BookingSettingsResponse getBookingSettings(Long ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return BookingSettingsResponse.of(owner, businessHourRepository.findByOwnerIdOrderByDayOfWeekAsc(ownerId));
    }

    @Transactional
    public BookingSettingsResponse updateBookingSettings(Long ownerId, BookingSettingsRequest request) {
        validate(request);
        checkLunchWithinBusinessHours(request);

        Owner owner = lockOwner(ownerId);
        Map<DayOfWeek, OwnerBusinessHour> current = businessHourRepository.findByOwnerIdOrderByDayOfWeekAsc(ownerId)
                .stream()
                .collect(Collectors.toMap(OwnerBusinessHour::getDayOfWeek, Function.identity()));
        for (BookingSettingsRequest.BusinessHour item : request.businessHours()) {
            DayOfWeek day = DayOfWeek.of(item.dayOfWeek());
            OwnerBusinessHour hour = current.get(day);
            if (hour == null) {
                businessHourRepository.save(OwnerBusinessHour.builder()
                        .owner(owner)
                        .dayOfWeek(day)
                        .openTime(item.openTime())
                        .closeTime(item.closeTime())
                        .isClosed(item.isClosed())
                        .build());
            } else {
                hour.update(item.openTime(), item.closeTime(), item.isClosed());
            }
        }
        owner.updateBookingRules(request.lunchStart(), request.lunchEnd(), request.maxDailyVisits(),
                request.bufferMinutes(), TravelMode.CAR);
        autoBookingSetup.keepMet(owner, AutoBookingRequirement.BUSINESS_HOURS);
        return BookingSettingsResponse.of(owner, businessHourRepository.findByOwnerIdOrderByDayOfWeekAsc(ownerId));
    }

    // 켤 때만 필수 설정 검사, 끄기는 언제나 가능 - 승인만으로는 켜지지 않음
    @Transactional
    public AutoBookingResponse changeAutoBooking(Long ownerId, boolean enabled) {
        Owner owner = lockOwner(ownerId);
        if (enabled) {
            autoBookingSetup.requireComplete(owner);
        }
        owner.changeAutoBooking(enabled);
        return new AutoBookingResponse(owner.isAutoBookingEnabled());
    }

    private static void validate(BookingSettingsRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        Set<Integer> days = new HashSet<>();
        for (int i = 0; i < request.businessHours().size(); i++) {
            BookingSettingsRequest.BusinessHour item = request.businessHours().get(i);
            String field = "businessHours[" + i + "]";
            if (!days.add(item.dayOfWeek())) {
                errors.add(new FieldErrorResponse(field + ".dayOfWeek", "같은 요일이 두 번 들어 있습니다"));
            }
            if (item.isClosed()) {
                if (item.openTime() != null || item.closeTime() != null) {
                    errors.add(new FieldErrorResponse(field + ".openTime", "쉬는 날은 시간을 null로 보내 주세요"));
                }
            } else if (item.openTime() == null || item.closeTime() == null) {
                errors.add(new FieldErrorResponse(field + ".openTime", "여는 날은 시작·끝 시간이 필요합니다"));
            } else if (!item.closeTime().isAfter(item.openTime())) {
                errors.add(new FieldErrorResponse(field + ".closeTime", "끝 시간이 시작 시간보다 늦어야 합니다"));
            }
        }
        if ((request.lunchStart() == null) != (request.lunchEnd() == null)) {
            errors.add(new FieldErrorResponse("lunchEnd", "점심 시작·끝은 함께 보내거나 둘 다 null로 보내 주세요"));
        } else if (request.lunchStart() != null && !request.lunchEnd().isAfter(request.lunchStart())) {
            errors.add(new FieldErrorResponse("lunchEnd", "점심 끝이 시작보다 늦어야 합니다"));
        }
        if (request.travelMode() != null && request.travelMode() != TravelMode.CAR) {
            errors.add(new FieldErrorResponse("travelMode", "지금은 CAR만 쓸 수 있습니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
    }

    // 여는 날마다 점심이 영업시간 안에 있어야 함 - 벗어난 요일을 알려 줌
    private static void checkLunchWithinBusinessHours(BookingSettingsRequest request) {
        LocalTime lunchStart = request.lunchStart();
        LocalTime lunchEnd = request.lunchEnd();
        if (lunchStart == null) {
            return;
        }
        List<Integer> daysOutside = request.businessHours().stream()
                .filter(item -> !item.isClosed())
                .filter(item -> lunchStart.isBefore(item.openTime()) || lunchEnd.isAfter(item.closeTime()))
                .map(BookingSettingsRequest.BusinessHour::dayOfWeek)
                .sorted()
                .toList();
        if (!daysOutside.isEmpty()) {
            throw new BusinessException(OwnerErrorCode.LUNCH_OUTSIDE_BUSINESS_HOURS, Map.of("daysOfWeek", daysOutside));
        }
    }

    private Owner lockOwner(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
