-- V7: 작업 메모는 예약당 1개 (B-24, BE-13)
--
-- 경로가 단수(work-note)이고 PUT은 메모 전체를 바꿈 → 같은 예약에 메모가 두 줄 생기지 않게 DB에서 막음
-- 중복이 이미 있으면 여기서 실패함 (메모를 임의로 지우지 않음)

ALTER TABLE work_note
    ADD CONSTRAINT uq_work_note_reservation UNIQUE (reservation_id);

-- UNIQUE가 같은 칸의 인덱스를 만들어 더 필요 없음
DROP INDEX ix_work_note_reservation;
