package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.owner.dto.OwnerBlockRequest;
import com.ilro.backend.domain.owner.dto.OwnerBlockResponse;
import com.ilro.backend.domain.owner.entity.OwnerBlock;
import com.ilro.backend.domain.owner.repository.OwnerBlockRepository;
import com.ilro.backend.domain.reservation.dto.ReservationSnapshot;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.service.ScheduleWriter;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

// 차단 시간 (B-22, 명세 4.6, BE-12) - 예약을 받지 않는 개인 일정 등
// 등록·수정·해제는 업체를 잠그고 (명세 1.7), 확정 예약과 겹치면 409 BLOCK_CONFLICTS_RESERVATION + 겹친 예약
// 사장님이 확인하고 acknowledgeConflicts: true로 다시 보내면 저장 - 겹친 예약은 지우지 않고 따로 조정 (변경 제안·취소)
@Service
@RequiredArgsConstructor
public class OwnerBlockService {

    // 한 번에 볼 수 있는 기간 (일)
    static final int MAX_RANGE_DAYS = 62;

    private final ScheduleWriter writer;
    private final OwnerBlockRepository blockRepository;
    private final ReservationRepository reservationRepository;

    // 기간(한국 날짜 from~to, 둘 다 포함)과 겹치는 차단 시간
    public List<OwnerBlockResponse> list(Long ownerId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        Instant start = from.atStartOfDay(KstTime.ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(KstTime.ZONE).toInstant();
        return writer.read(() -> blockRepository.findOverlapping(ownerId, start, end).stream()
                .map(OwnerBlockResponse::from)
                .toList());
    }

    public OwnerBlockResponse create(Long ownerId, OwnerBlockRequest request) {
        Instant[] range = range(request);
        return writer.locked(ownerId, owner -> {
            checkConflicts(ownerId, range, request.acknowledgeConflicts());
            return OwnerBlockResponse.from(blockRepository.save(OwnerBlock.builder()
                    .owner(owner)
                    .startAt(range[0])
                    .endAt(range[1])
                    .reason(blankToNull(request.reason()))
                    .build()));
        });
    }

    public OwnerBlockResponse update(Long ownerId, Long blockId, OwnerBlockRequest request) {
        Instant[] range = range(request);
        return writer.locked(ownerId, owner -> {
            OwnerBlock block = findOwned(ownerId, blockId);
            checkConflicts(ownerId, range, request.acknowledgeConflicts());
            block.update(range[0], range[1], blankToNull(request.reason()));
            blockRepository.flush();
            return OwnerBlockResponse.from(block);
        });
    }

    public void delete(Long ownerId, Long blockId) {
        writer.locked(ownerId, owner -> {
            blockRepository.delete(findOwned(ownerId, blockId));
            return null;
        });
    }

    private OwnerBlock findOwned(Long ownerId, Long blockId) {
        return blockRepository.findById(blockId)
                .filter(block -> block.getOwner().getId().equals(ownerId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.BLOCK_NOT_FOUND));
    }

    private void checkConflicts(Long ownerId, Instant[] range, boolean acknowledged) {
        if (acknowledged) {
            return;
        }
        List<ReservationSnapshot> conflicts = reservationRepository
                .findOverlappingWithStatus(ownerId, range[0], range[1], ReservationStatus.CONFIRMED).stream()
                .map(ReservationSnapshot::from)
                .toList();
        if (!conflicts.isEmpty()) {
            throw new BusinessException(ReservationErrorCode.BLOCK_CONFLICTS_RESERVATION,
                    Collections.singletonMap("reservations", conflicts));
        }
    }

    // 분 단위, 끝은 시작보다 뒤
    private static Instant[] range(OwnerBlockRequest request) {
        Instant start = request.startAt().toInstant().truncatedTo(ChronoUnit.MINUTES);
        Instant end = request.endAt().toInstant().truncatedTo(ChronoUnit.MINUTES);
        if (!end.isAfter(start)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("endAt", "시작보다 뒤여야 합니다")));
        }
        return new Instant[]{start, end};
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("to", "from보다 앞설 수 없습니다")));
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("to", MAX_RANGE_DAYS + "일까지 볼 수 있습니다")));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
