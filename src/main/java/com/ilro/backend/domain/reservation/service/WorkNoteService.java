package com.ilro.backend.domain.reservation.service;

import com.ilro.backend.domain.reservation.dto.WorkNoteRequest;
import com.ilro.backend.domain.reservation.dto.WorkNoteResponse;
import com.ilro.backend.domain.reservation.entity.Reservation;
import com.ilro.backend.domain.reservation.entity.WorkNote;
import com.ilro.backend.domain.reservation.exception.ReservationErrorCode;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.domain.reservation.repository.WorkNoteRepository;
import com.ilro.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 작업 메모 (B-24, BE-13) - 사장님 전용, 예약 상태와 상관없이 쓰고 고칠 수 있음 (완료 뒤 기록)
// 예약 버전은 올리지 않음 - 메모를 고쳤다고 변경·취소 화면이 버전 충돌을 내지 않게
// 업체를 잠가 첫 저장이 동시에 와도 메모가 하나만 생김 (나중에 저장한 내용이 남음)
@Service
@RequiredArgsConstructor
public class WorkNoteService {

    private final ScheduleWriter writer;
    private final ReservationRepository reservationRepository;
    private final WorkNoteRepository workNoteRepository;

    public WorkNoteResponse get(Long ownerId, Long reservationId) {
        return writer.read(() -> {
            find(ownerId, reservationId);
            return workNoteRepository.findByReservationId(reservationId)
                    .map(note -> WorkNoteResponse.of(reservationId, note))
                    .orElseGet(() -> WorkNoteResponse.empty(reservationId));
        });
    }

    public WorkNoteResponse save(Long ownerId, Long reservationId, WorkNoteRequest request) {
        return writer.locked(ownerId, owner -> {
            Reservation reservation = find(ownerId, reservationId);
            WorkNote note = workNoteRepository.findByReservationId(reservationId)
                    .orElseGet(() -> WorkNote.builder().reservation(reservation).build());
            note.updateContent(request.content().trim());
            // 수정 시각은 DB에 반영할 때 채워지므로 응답 전에 바로 반영
            return WorkNoteResponse.of(reservationId, workNoteRepository.saveAndFlush(note));
        });
    }

    // 다른 업체 예약은 없는 것처럼 404
    private Reservation find(Long ownerId, Long reservationId) {
        return reservationRepository.findByIdAndOwnerId(reservationId, ownerId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
}
