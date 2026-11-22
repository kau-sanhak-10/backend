-- V5: 예약 변경 요청의 '고객이 요청한 주소'를 제안 주소와 따로 보관, 수동 예약의 영업시간 밖 등록 여부 보관 (BE-12)
--
-- 1. reservation_change.requested_* : 고객이 변경 요청에 적은 주소
--    지금까지는 proposed_*에 같이 넣어서, 사장님이 주소를 바꾸지 않는 시간 제안을 하면 요청 주소가 지워졌음
--    → 그 뒤 제안을 고쳐 요청 주소를 반영하려 해도 주소가 없어 400
--    proposed_*는 '그 제안이 반영할 주소'만 (주소를 바꾸지 않는 제안이면 NULL)
-- 2. reservation.outside_business_hours : 영업시간 밖 경고를 확인하고 등록한 수동 예약인지
--    같은 키로 다시 요청했을 때 처음 결과를 그대로 돌려주려고 저장 (나중에 영업시간이 바뀌어도 값이 변하지 않게)

ALTER TABLE reservation_change
    ADD COLUMN requested_address VARCHAR(255),
    ADD COLUMN requested_lat     NUMERIC(10, 7),
    ADD COLUMN requested_lng     NUMERIC(10, 7),
    ADD CONSTRAINT ck_reservation_change_requested_lat CHECK (requested_lat BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_reservation_change_requested_lng CHECK (requested_lng BETWEEN -180 AND 180);

-- 고객 요청에 들어 있던 주소를 요청 주소로 옮김
UPDATE reservation_change
SET requested_address = proposed_address,
    requested_lat     = proposed_lat,
    requested_lng     = proposed_lng
WHERE requested_by = 'CUSTOMER'
  AND proposed_address IS NOT NULL;

-- 아직 사장님 제안이 없는 요청은 제안 주소를 비움
UPDATE reservation_change
SET proposed_address = NULL,
    proposed_lat     = NULL,
    proposed_lng     = NULL
WHERE proposed_start_at IS NULL;

ALTER TABLE reservation
    ADD COLUMN outside_business_hours BOOLEAN NOT NULL DEFAULT false;
