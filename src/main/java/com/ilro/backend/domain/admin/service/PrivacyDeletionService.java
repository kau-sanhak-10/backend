package com.ilro.backend.domain.admin.service;

import com.ilro.backend.domain.admin.dto.PrivacyDeletionRequest;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionRequest.Basis;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse.Deleted;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse.Failed;
import com.ilro.backend.domain.admin.exception.AdminErrorCode;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 개인정보 삭제 (O-09, 명세 4.8, BE-19) - SUPER 운영자만, 문의 단위
// 1) 처리 근거(basis)가 있어야 함 - 근거가 없거나 보관 필요 여부가 불명확하면 실행하지 않음
//    보관기간이 지났다는 근거(RETENTION_EXPIRED)는 팀이 보관기간을 정하기 전까지 실행하지 않음 (임의의 기간을 만들지 않음)
// 2) 요청한 문의를 모두 먼저 확인 - 하나라도 지울 수 없으면 422 PRIVACY_DELETE_NOT_ALLOWED + 이유, 아무것도 지우지 않음
// 3) 문의마다 따로 지움 - 하나가 실패해도 나머지는 계속, 결과는 처리된 범위(deleted)와 실패한 범위(failed)로 나눔
// 실행자·대상·결과는 접근 기록(AccessLog)으로 남김 - 문의마다 한 줄, 요청 전체 한 줄 (일부 실패면 PARTIAL)
@Slf4j
@Service
public class PrivacyDeletionService {

    private final InquiryDataEraser eraser;
    private final AdminAccess adminAccess;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    // 보관기간(일) - 정해지지 않았으면 null
    private final Integer retentionDays;

    public PrivacyDeletionService(InquiryDataEraser eraser, AdminAccess adminAccess, JdbcTemplate jdbc, Clock clock,
                                  @Value("${app.privacy.retention-days:}") Integer retentionDays) {
        this.eraser = eraser;
        this.adminAccess = adminAccess;
        this.jdbc = jdbc;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    public PrivacyDeletionResponse delete(AuthPrincipal principal, PrivacyDeletionRequest request) {
        adminAccess.requireSuper(principal);
        List<Long> inquiryIds = request.inquiryIds().stream().distinct().toList();
        Instant now = clock.instant();

        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Long inquiryId : inquiryIds) {
            notAllowed(inquiryId, request.basis(), now).ifPresent(reason -> reasons.add(reason(inquiryId, reason)));
        }
        if (!reasons.isEmpty()) {
            AccessLog.record(principal, "DELETE", "privacy_deletion", null, AccessLog.Result.FAILED);
            throw new BusinessException(AdminErrorCode.PRIVACY_DELETE_NOT_ALLOWED, Map.of("reasons", reasons));
        }

        List<Deleted> deleted = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (Long inquiryId : inquiryIds) {
            try {
                deleted.add(eraser.erase(inquiryId, now));
                AccessLog.record(principal, "DELETE", "inquiry", inquiryId);
            } catch (RuntimeException e) {
                failed.add(new Failed(inquiryId, failureReason(e)));
                AccessLog.record(principal, "DELETE", "inquiry", inquiryId, AccessLog.Result.FAILED);
                log.warn("Privacy deletion failed: inquiryId={}, reason={}", inquiryId, failureReason(e), e);
            }
        }
        AccessLog.Result result = failed.isEmpty() ? AccessLog.Result.OK
                : deleted.isEmpty() ? AccessLog.Result.FAILED : AccessLog.Result.PARTIAL;
        AccessLog.record(principal, "DELETE", "privacy_deletion", null, result);
        log.info("Privacy deletion: basis={}, reference={}, requested={}, deleted={}, failed={}, adminId={}",
                request.basis(), request.reference(), inquiryIds.size(), deleted.size(), failed.size(),
                principal.adminId());
        return new PrivacyDeletionResponse(deleted, failed);
    }

    // 지울 수 없는 이유 - 없으면 empty
    private Optional<String> notAllowed(Long inquiryId, Basis basis, Instant now) {
        Optional<InquiryDataEraser.Blocker> blocker = eraser.blocker(inquiryId);
        if (blocker.isPresent()) {
            return blocker.map(Enum::name);
        }
        if (basis == Basis.RETENTION_EXPIRED) {
            if (retentionDays == null) {
                return Optional.of("RETENTION_PERIOD_UNDEFINED");
            }
            // 마지막으로 바뀐 때부터 보관기간이 지나야 함
            Timestamp updatedAt = jdbc.queryForObject("SELECT updated_at FROM inquiry WHERE id = ?", Timestamp.class,
                    inquiryId);
            if (updatedAt.toInstant().isAfter(now.minus(Duration.ofDays(retentionDays)))) {
                return Optional.of("RETENTION_NOT_EXPIRED");
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> reason(Long inquiryId, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("inquiryId", inquiryId);
        entry.put("reason", reason);
        return entry;
    }

    private static String failureReason(RuntimeException e) {
        if (e instanceof InquiryDataEraser.BlockedException blocked) {
            return blocked.blocker().name();
        }
        return e instanceof InquiryDataEraser.PhotoDeleteException ? "PHOTO_DELETE_FAILED" : "DATA_DELETE_FAILED";
    }
}
