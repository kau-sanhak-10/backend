-- V6: 알림 재시도 시각 (BE-14 발송기)
--
-- 지금까지는 재시도 차례를 '시도 횟수 + 알림 생성 시각'으로 계산했음 (생성 1분 뒤·5분 뒤)
-- → 알림이 큐에서 5분 넘게 기다린 뒤 실패하면 두 기준이 이미 지나 있어 10초 간격으로 3번을 다 써 버림
-- 실패할 때 다음 시도 시각을 저장하고 그 시각부터 다시 고름 (실패 1분 뒤·5분 뒤)
-- 첫 시도(attempt_count = 0)는 이 값 없이 바로

ALTER TABLE notification
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- 이미 실패해 재시도를 기다리는 알림은 바로 다음 차례에
UPDATE notification
SET next_attempt_at = CURRENT_TIMESTAMP
WHERE send_status = 'FAILED'
  AND attempt_count BETWEEN 1 AND 2;

-- 발송기가 고르는 조건(보낼 상태 + 다음 시도 시각)에 맞춤
DROP INDEX ix_notification_retry;
CREATE INDEX ix_notification_retry ON notification (send_status, next_attempt_at) WHERE send_status IN ('PENDING', 'FAILED');
