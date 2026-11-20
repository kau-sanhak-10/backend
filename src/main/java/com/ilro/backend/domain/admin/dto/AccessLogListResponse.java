package com.ilro.backend.domain.admin.dto;

import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.infra.logging.AccessLogEntry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

// 고객정보 접근 기록 (O-09, 명세 5.9) - 최근 것부터. 전화번호·주소·대화 원문은 없음
// 목록 공통 형식 (명세 1.2). truncated: 기록이 너무 많아 일부만 읽음 - 기간이나 대상을 좁혀 다시 조회
public record AccessLogListResponse(List<Item> content, int page, int size, long totalElements, int totalPages,
                                    boolean isLast, boolean truncated) {

    // result: SUCCESS / PARTIAL(일부만 처리) / FAILED
    public record Item(OffsetDateTime at, String actorType, Long actorId, String action, String targetType,
                       Long targetId, String result) {

        public static Item from(AccessLogEntry entry) {
            return new Item(KstTime.of(entry.at()), entry.actorType(), entry.actorId(), entry.action(),
                    entry.targetType().toUpperCase(Locale.ROOT), entry.targetId(),
                    "OK".equals(entry.result()) ? "SUCCESS" : entry.result());
        }
    }
}
