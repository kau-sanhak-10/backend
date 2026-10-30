package com.ilro.backend.domain.auth.service;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.auth.entity.AuthToken;
import com.ilro.backend.domain.auth.repository.AuthTokenRepository;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

// 로그인·접근 토큰 발급·확인·철회 (명세 1.5)
// 토큰 = 앞머리 + 무작위 32바이트, DB에는 SHA-256 해시만 저장 (JWT 아님, 서버에서 바로 철회 가능)
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    // 로그인부터 최대 7일, 쓰는 동안 연장하지 않음
    public static final Duration OWNER_TOKEN_TTL = Duration.ofDays(7);
    public static final Duration ADMIN_TOKEN_TTL = Duration.ofHours(12);

    // 마지막 사용 시각은 1분에 한 번만 기록 (요청마다 쓰지 않게)
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthTokenRepository authTokenRepository;
    private final Clock clock;

    // 토큰 원문은 응답으로 한 번만 돌려주고 저장하지 않음
    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issueOwnerToken(Owner owner) {
        String token = newToken("own_");
        Instant expiresAt = clock.instant().plus(OWNER_TOKEN_TTL);
        authTokenRepository.save(AuthToken.ownerToken(hash(token), owner, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    public IssuedToken issueAdminToken(Admin admin) {
        String token = newToken("adm_");
        Instant expiresAt = clock.instant().plus(ADMIN_TOKEN_TTL);
        authTokenRepository.save(AuthToken.adminToken(hash(token), admin, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    // 없음·철회 → 401 UNAUTHORIZED, 만료 → 401 TOKEN_EXPIRED
    @Transactional
    public AuthPrincipal authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        AuthToken authToken = authTokenRepository.findByTokenHash(hash(token))
                .filter(found -> found.getRevokedAt() == null)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        Instant now = clock.instant();
        if (!now.isBefore(authToken.getExpiresAt())) {
            throw new BusinessException(CommonErrorCode.TOKEN_EXPIRED);
        }
        if (authToken.getLastUsedAt() == null || authToken.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
            authTokenRepository.touch(authToken.getId(), now);
        }
        return AuthPrincipal.from(authToken);
    }

    // 로그아웃 - 지금 쓰는 토큰만
    @Transactional
    public void revoke(Long tokenId) {
        authTokenRepository.revoke(tokenId, clock.instant());
    }

    // 로그인 번호 변경·이용 제한·탈퇴 때 그 업체의 사장님 토큰 전부 (O-02·O-03에서 사용)
    @Transactional
    public int revokeOwnerTokens(Long ownerId) {
        return authTokenRepository.revokeOwnerTokens(ownerId, clock.instant());
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
