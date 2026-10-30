package com.ilro.backend.domain.auth.repository;

import com.ilro.backend.domain.auth.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update AuthToken t set t.lastUsedAt = :now where t.id = :id")
    int touch(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("update AuthToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("now") Instant now);

    // 로그인 번호 변경·이용 제한·탈퇴 때 그 업체의 사장님 토큰을 한 번에 철회
    @Modifying
    @Query("update AuthToken t set t.revokedAt = :now where t.owner.id = :ownerId "
            + "and t.tokenType = com.ilro.backend.domain.auth.entity.AuthTokenType.OWNER and t.revokedAt is null")
    int revokeOwnerTokens(@Param("ownerId") Long ownerId, @Param("now") Instant now);
}
