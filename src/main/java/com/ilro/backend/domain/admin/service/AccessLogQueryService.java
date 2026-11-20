package com.ilro.backend.domain.admin.service;

import com.ilro.backend.domain.admin.dto.AccessLogListResponse;
import com.ilro.backend.domain.admin.exception.AdminErrorCode;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import com.ilro.backend.infra.logging.AccessLogEntry;
import com.ilro.backend.infra.logging.AccessLogQueryException;
import com.ilro.backend.infra.logging.AccessLogReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 고객정보 접근 기록 조회 (O-09, 명세 5.9, BE-19) - SUPER 운영자만
// 고객정보 조회·수정·삭제 API가 남긴 서버 로그(AccessLog)를 읽어 보여 줌. DB 감사 테이블은 없음
// 한 번에 31일까지, 읽지 못하면 502 (0건으로 답하지 않음), 조회한 사실도 접근 기록으로 남김
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogQueryService {

    static final int MAX_DAYS = 31;
    private static final int DEFAULT_DAYS = 7;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    // 대상 종류 - AccessLog가 쓰는 이름 (inquiry·reservation·owner·owner.home 등), 대소문자 무시
    private static final String TARGET_TYPE = "[A-Za-z][A-Za-z0-9_.]{0,39}";

    private final AccessLogReader reader;
    private final AdminAccess adminAccess;
    private final Clock clock;

    // from·to: 한국 날짜 (둘 다 포함). 안 보내면 오늘까지 7일
    public AccessLogListResponse search(AuthPrincipal principal, String targetType, Long targetId, LocalDate from,
                                        LocalDate to, Integer page, Integer size) {
        adminAccess.requireSuper(principal);
        LocalDate last = to != null ? to : LocalDate.now(clock.withZone(KstTime.ZONE));
        LocalDate first = from != null ? from : last.minusDays(DEFAULT_DAYS - 1L);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;

        List<FieldErrorResponse> errors = new ArrayList<>();
        if (first.isAfter(last)) {
            errors.add(new FieldErrorResponse("from", "to보다 늦을 수 없습니다"));
        } else if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            errors.add(new FieldErrorResponse("to", "한 번에 " + MAX_DAYS + "일까지 조회할 수 있습니다"));
        }
        if (targetType != null && !targetType.matches(TARGET_TYPE)) {
            errors.add(new FieldErrorResponse("targetType", "대상 종류 형식이 아닙니다"));
        }
        if (targetId != null && targetType == null) {
            errors.add(new FieldErrorResponse("targetType", "targetId를 보낼 때는 targetType도 필요합니다"));
        }
        if (pageNumber < 0 || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            errors.add(new FieldErrorResponse("page", "0 이상의 쪽 번호를 보내 주세요"));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            errors.add(new FieldErrorResponse("size", "1 ~ " + MAX_PAGE_SIZE + " 사이여야 합니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }

        AccessLogReader.Result found;
        try {
            found = reader.read(new AccessLogReader.Query(targetType, targetId,
                    first.atStartOfDay(KstTime.ZONE).toInstant(), last.plusDays(1).atStartOfDay(KstTime.ZONE).toInstant()));
        } catch (AccessLogQueryException e) {
            log.warn("Access log query failed: {}", e.getCause() == null ? e.getMessage()
                    : e.getCause().getClass().getSimpleName());
            throw new BusinessException(AdminErrorCode.ACCESS_LOG_QUERY_FAILED);
        }

        List<AccessLogEntry> sorted = found.entries().stream()
                .sorted(Comparator.comparing(AccessLogEntry::at).reversed())
                .toList();
        long offset = (long) pageNumber * pageSize;
        List<AccessLogListResponse.Item> content = sorted.stream()
                .skip(offset)
                .limit(pageSize)
                .map(AccessLogListResponse.Item::from)
                .toList();
        int totalPages = (int) Math.ceil(sorted.size() / (double) pageSize);
        // 접근 기록을 본 것도 기록 - 어느 대상의 기록을 봤는지까지
        AccessLog.record(principal, "VIEW", "access_log", targetId);
        return new AccessLogListResponse(content, pageNumber, pageSize, sorted.size(), totalPages,
                offset + content.size() >= sorted.size(), found.truncated());
    }
}
