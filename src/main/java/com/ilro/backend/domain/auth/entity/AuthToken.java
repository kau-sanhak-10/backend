package com.ilro.backend.domain.auth.entity;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 로그인·접근 토큰 1개 - 요청마다 해시로 조회해 만료·철회 확인 (JWT 아님, Redis 미사용)
// 종류별 대상 칸 조합이 DB 제약(ck_auth_token_subject)과 맞도록 종류별 생성 메서드로만 만듦
@Getter
@Entity
@Table(name = "auth_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthToken extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 토큰 원문의 SHA-256 해시 (원문 저장 금지)
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthTokenType tokenType;

    // OWNER: 그 사장님 업체, CUSTOMER: 조회 범위 업체
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Owner owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id")
    private Admin admin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id")
    private Inquiry inquiry;

    // CUSTOMER: 인증한 번호
    @Column(length = 20)
    private String customerPhone;

    // CUSTOMER만
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AuthTokenScope scope;

    @Column(nullable = false)
    private Instant expiresAt;

    // 값이 있으면 철회된 토큰 (로그아웃, 로그인 번호 변경, 이용 제한 등)
    private Instant revokedAt;

    private Instant lastUsedAt;

    private AuthToken(String tokenHash, AuthTokenType tokenType, Owner owner, Admin admin, Inquiry inquiry,
                      String customerPhone, AuthTokenScope scope, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.tokenType = tokenType;
        this.owner = owner;
        this.admin = admin;
        this.inquiry = inquiry;
        this.customerPhone = customerPhone;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    public static AuthToken ownerToken(String tokenHash, Owner owner, Instant expiresAt) {
        return new AuthToken(tokenHash, AuthTokenType.OWNER, owner, null, null, null, null, expiresAt);
    }

    public static AuthToken adminToken(String tokenHash, Admin admin, Instant expiresAt) {
        return new AuthToken(tokenHash, AuthTokenType.ADMIN, null, admin, null, null, null, expiresAt);
    }

    public static AuthToken inquiryToken(String tokenHash, Inquiry inquiry, Instant expiresAt) {
        return new AuthToken(tokenHash, AuthTokenType.INQUIRY, null, null, inquiry, null, null, expiresAt);
    }

    public static AuthToken customerToken(String tokenHash, Owner owner, String customerPhone, AuthTokenScope scope,
                                          Instant expiresAt) {
        return new AuthToken(tokenHash, AuthTokenType.CUSTOMER, owner, null, null, customerPhone, scope, expiresAt);
    }
}
