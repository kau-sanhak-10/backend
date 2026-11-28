package com.ilro.backend.infra.logging;

import java.time.Instant;
import java.util.List;

// 접근 기록 읽기 (O-09, 명세 5.9) - 서버(dev·prod)는 CloudWatch, 로컬·테스트는 이 서버가 남긴 기록(메모리)
// DB 감사 테이블은 없음 - AccessLog가 남긴 서버 로그가 기록의 전부
public interface AccessLogReader {

    // targetType·targetId는 null이면 거르지 않음 (targetType은 대소문자 무시), 기간은 [from, to)
    record Query(String targetType, Long targetId, Instant from, Instant to) {
    }

    // truncated: 기록이 너무 많아 일부만 읽음 - 기간이나 대상을 좁혀 다시 조회해야 함
    record Result(List<AccessLogEntry> entries, boolean truncated) {
    }

    // 읽지 못하면 AccessLogQueryException - 0건으로 답하지 않음
    Result read(Query query);
}
