package com.ilro.backend.domain.admin.service;

import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse.Deleted;
import com.ilro.backend.domain.admin.dto.PrivacyDeletionResponse.Kept;
import com.ilro.backend.infra.storage.PhotoStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// 문의 한 건의 고객 데이터를 지움 (O-09, BE-19) - 문의마다 따로 트랜잭션, 문의 행을 잠그고 시작
// 지우는 것: 대화 내용, 사진(파일과 기록), 접수 항목, AI에 보낸 대화·AI 응답 원문, 회신 요청의 이름·번호,
//           문의의 방문 주소·증상·제품 정보·AI 요약, 문의 접근 토큰, 그 문의의 문자 인증 기록
// 남기는 것: 동의 기록(무엇에 언제 동의했는지 - 개인정보 없음), 예약 기록과 그 예약의 고객 이름·번호
//           (예약 기록의 보관 기준이 정해질 때까지), 알림 기록(대상 번호만 가리킴)
// 문의 행은 지웠다는 표시(deleted_at)만 남김 - 목록·상세에서 없는 문의로 보임
// 여러 표를 한꺼번에 정해진 순서로 지워야 해서 엔티티 대신 SQL로 직접 씀
@Component
@RequiredArgsConstructor
public class InquiryDataEraser {

    // 지울 수 없는 이유 - 잠근 뒤에도 다시 확인
    public enum Blocker {
        INQUIRY_NOT_FOUND, OPEN_INQUIRY, ACTIVE_RESERVATION
    }

    // 잠근 뒤 확인에서 걸림 - 트랜잭션을 되돌리고 부르는 쪽이 실패 범위로 기록
    public static final class BlockedException extends RuntimeException {

        private final Blocker blocker;

        BlockedException(Blocker blocker) {
            super(blocker.name(), null, false, false);
            this.blocker = blocker;
        }

        public Blocker blocker() {
            return blocker;
        }
    }

    // 사진 파일 삭제 실패 - 기록은 그대로 둬서 다시 실행하면 이어서 지움
    public static final class PhotoDeleteException extends RuntimeException {

        PhotoDeleteException(Throwable cause) {
            super("photo delete failed", cause);
        }
    }

    private final JdbcTemplate jdbc;
    private final PhotoStorage photoStorage;

    // 지금 지울 수 있는지만 봄 (잠그지 않음) - 요청 전체를 실행하기 전에 한 번에 확인하는 데 씀
    @Transactional(readOnly = true)
    public Optional<Blocker> blocker(Long inquiryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, deleted_at FROM inquiry WHERE id = ?", inquiryId);
        return rows.isEmpty() ? Optional.of(Blocker.INQUIRY_NOT_FOUND) : blockerOf(inquiryId, rows.getFirst());
    }

    @Transactional
    public Deleted erase(Long inquiryId, Instant now) {
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, deleted_at, customer_id FROM inquiry WHERE id = ? FOR UPDATE", inquiryId);
        if (rows.isEmpty()) {
            throw new BlockedException(Blocker.INQUIRY_NOT_FOUND);
        }
        Map<String, Object> inquiry = rows.getFirst();
        blockerOf(inquiryId, inquiry).ifPresent(blocker -> {
            throw new BlockedException(blocker);
        });

        // 1. 사진 파일 - 저장소에서 먼저 지움. 실패하면 기록은 그대로 두고 끝냄 (없는 파일 삭제는 성공이라 다시 실행 가능)
        List<String> keys = new ArrayList<>();
        jdbc.query("SELECT s3_key_original, s3_key_resized FROM photo WHERE inquiry_id = ?", (RowCallbackHandler) rs -> {
            keys.add(rs.getString(1));
            keys.add(rs.getString(2));
        }, inquiryId);
        try {
            keys.stream().filter(Objects::nonNull).forEach(photoStorage::delete);
        } catch (RuntimeException e) {
            throw new PhotoDeleteException(e);
        }

        // 2. 기록 - 접수 항목이 사진·메시지를 가리키므로 항목부터
        int fields = jdbc.update("DELETE FROM inquiry_field WHERE inquiry_id = ?", inquiryId);
        int photos = jdbc.update("DELETE FROM photo WHERE inquiry_id = ?", inquiryId);
        int messages = jdbc.update("UPDATE message SET content = NULL WHERE inquiry_id = ? AND content IS NOT NULL",
                inquiryId);
        jdbc.update("UPDATE ai_decision SET input_snapshot = NULL, output_raw = NULL WHERE inquiry_id = ?", inquiryId);
        jdbc.update("DELETE FROM handoff_request WHERE inquiry_id = ?", inquiryId);
        jdbc.update("DELETE FROM phone_verification WHERE target_inquiry_id = ?", inquiryId);
        Timestamp at = Timestamp.from(now);
        jdbc.update("UPDATE auth_token SET revoked_at = ? WHERE inquiry_id = ? AND revoked_at IS NULL", at, inquiryId);
        jdbc.update("UPDATE inquiry SET symptom = NULL, address = NULL, address_detail = NULL, lat = NULL, "
                + "lng = NULL, access_note = NULL, manufacturer = NULL, model = NULL, boiler_type = NULL, "
                + "error_code = NULL, error_code_id = NULL, ai_summary = NULL, visit_info_source = NULL, "
                + "customer_id = NULL, phone_verified_at = NULL, deleted_at = COALESCE(deleted_at, ?), "
                + "updated_at = ?, version = version + 1 WHERE id = ?", at, at, inquiryId);

        // 3. 고객 이름·번호 - 예약 기록이나 다른 문의가 가리키면 남김
        List<Kept> kept = new ArrayList<>();
        boolean customerDeleted = false;
        Long customerId = (Long) inquiry.get("customer_id");
        if (customerId != null) {
            customerDeleted = jdbc.update("DELETE FROM customer c WHERE c.id = ? "
                    + "AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.customer_id = c.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM inquiry i WHERE i.customer_id = c.id)", customerId) > 0;
        }
        if (exists("SELECT 1 FROM reservation WHERE inquiry_id = ?", inquiryId)
                || (customerId != null && exists("SELECT 1 FROM reservation WHERE customer_id = ?", customerId))) {
            kept.add(Kept.RESERVATION_RECORD);
        }
        if (exists("SELECT 1 FROM consent WHERE inquiry_id = ?", inquiryId)) {
            kept.add(Kept.CONSENT_RECORD);
        }
        return new Deleted(inquiryId, messages, photos, fields, customerDeleted, kept);
    }

    // 이미 지운 문의는 다시 지워도 됨 (아무것도 남아 있지 않음)
    // 진행 중인 상담(정보 수집·시간 선택·사장님 확인)과 진행 중인 예약(요청·확정)이 있으면 막음
    private Optional<Blocker> blockerOf(Long inquiryId, Map<String, Object> inquiry) {
        if (inquiry.get("deleted_at") == null
                && List.of("COLLECTING", "SCHEDULING", "OWNER_CHECK").contains((String) inquiry.get("status"))) {
            return Optional.of(Blocker.OPEN_INQUIRY);
        }
        if (exists("SELECT 1 FROM reservation WHERE inquiry_id = ? AND status IN ('REQUESTED', 'CONFIRMED')",
                inquiryId)) {
            return Optional.of(Blocker.ACTIVE_RESERVATION);
        }
        return Optional.empty();
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (" + sql + ")", Boolean.class, args));
    }
}
