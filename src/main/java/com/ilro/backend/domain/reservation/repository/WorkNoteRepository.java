package com.ilro.backend.domain.reservation.repository;

import com.ilro.backend.domain.reservation.entity.WorkNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkNoteRepository extends JpaRepository<WorkNote, Long> {

    // 예약당 1개 (uq_work_note_reservation)
    Optional<WorkNote> findByReservationId(Long reservationId);
}
