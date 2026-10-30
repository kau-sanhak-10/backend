package com.ilro.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// 커밋되는 통합 테스트용 업체·운영자·토큰 - 만든 것을 기억했다가 cleanUp()에서 지움
// (요청 하나가 트랜잭션 여러 개를 쓰는 흐름은 테스트 롤백으로 확인할 수 없어서 실제로 저장함)
public class TestAccounts {

    private final JdbcTemplate jdbc;
    private final List<Long> ownerIds = new ArrayList<>();
    private final List<Long> adminIds = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();

    public TestAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 다른 테스트·로컬 데이터와 겹치지 않는 휴대전화 번호 (숫자만)
    public String newPhone() {
        String phone = "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        phones.add(phone);
        return phone;
    }

    // 테스트마다 다른 요청 IP - IP별 누적 발송 제한이 테스트끼리 섞이지 않게
    public static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    public long approvedOwner(String phone) {
        return owner(phone, "APPROVED", "ACTIVE", null);
    }

    public long owner(String phone, String approvalStatus, String status, String rejectionReason) {
        long ownerId = jdbc.queryForObject(
                "INSERT INTO owner (business_name, phone, link_token, approval_status, status, rejection_reason) "
                        + "VALUES ('테스트보일러', ?, ?, ?, ?, ?) RETURNING id",
                Long.class, phone, "link-" + UUID.randomUUID(), approvalStatus, status, rejectionReason);
        ownerIds.add(ownerId);
        return ownerId;
    }

    public long admin(String phone) {
        long adminId = jdbc.queryForObject(
                "INSERT INTO admin (name, phone) VALUES ('운영자', ?) RETURNING id", Long.class, phone);
        adminIds.add(adminId);
        return adminId;
    }

    // 로그인 흐름 없이 바로 쓰는 사장님 토큰 (원문 반환, DB에는 해시)
    public String ownerToken(long ownerId) {
        return token("OWNER", ownerId, null, null, null, "CURRENT_TIMESTAMP + interval '1 day'");
    }

    public String token(String tokenType, Long ownerId, Long adminId, String customerPhone, String scope,
                        String expiresAtSql) {
        String raw = tokenType.toLowerCase().substring(0, 3) + "_" + UUID.randomUUID();
        jdbc.update("INSERT INTO auth_token (token_hash, token_type, owner_id, admin_id, customer_phone, scope, "
                        + "created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, " + expiresAtSql + " - interval '2 days', "
                        + expiresAtSql + ")",
                sha256(raw), tokenType, ownerId, adminId, customerPhone, scope);
        return raw;
    }

    public void cleanUp() {
        for (Long ownerId : ownerIds) {
            jdbc.update("DELETE FROM auth_token WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM phone_verification WHERE target_owner_id = ?", ownerId);
            jdbc.update("DELETE FROM reservation WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owner_block WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owner_business_hour WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owner_service_area WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owner_work_type WHERE owner_id = ?", ownerId);
            jdbc.update("DELETE FROM owner WHERE id = ?", ownerId);
        }
        for (Long adminId : adminIds) {
            jdbc.update("DELETE FROM auth_token WHERE admin_id = ?", adminId);
            jdbc.update("DELETE FROM admin WHERE id = ?", adminId);
        }
        for (String phone : phones) {
            jdbc.update("DELETE FROM phone_verification WHERE phone = ?", phone);
        }
    }

    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
