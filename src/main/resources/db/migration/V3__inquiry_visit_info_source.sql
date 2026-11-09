-- =====================================================================
-- V3: 방문정보를 마지막으로 정한 쪽 (B-14 사장님 수정 보호)
--   사장님이 고친 방문정보(OWNER)는 고객이 방문정보 저장(C-06)으로 다른 값으로 바꾸지 못함 - 바꿀 내용은 대화로
--   방문정보는 항목별 이력 테이블이 없어서, 덮어쓰지 못하게 막는 것으로 사장님 값을 지킴
--   기존 행은 누가 정했는지 기록이 없어 NULL (고객 값으로 봄)
-- =====================================================================

ALTER TABLE inquiry ADD COLUMN visit_info_source VARCHAR(20);

ALTER TABLE inquiry
    ADD CONSTRAINT ck_inquiry_visit_info_source CHECK (visit_info_source IN ('CUSTOMER', 'OWNER'));
