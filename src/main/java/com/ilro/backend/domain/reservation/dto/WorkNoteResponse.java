package com.ilro.backend.domain.reservation.dto;

import com.ilro.backend.domain.reservation.entity.WorkNote;
import com.ilro.backend.global.common.util.KstTime;

import java.time.OffsetDateTime;

// 작업 메모 (B-24, BE-13) - 사장님 API에서만 (명세 1.8)
// 아직 메모가 없으면 content는 빈 문자열, updatedAt은 null
public record WorkNoteResponse(Long reservationId, String content, OffsetDateTime updatedAt) {

    public static WorkNoteResponse of(Long reservationId, WorkNote note) {
        return new WorkNoteResponse(reservationId, note.getContent(), KstTime.of(note.getUpdatedAt()));
    }

    public static WorkNoteResponse empty(Long reservationId) {
        return new WorkNoteResponse(reservationId, "", null);
    }
}
