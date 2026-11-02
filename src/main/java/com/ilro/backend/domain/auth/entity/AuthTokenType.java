package com.ilro.backend.domain.auth.entity;

// 토큰 종류 - 종류마다 쓰는 대상 칸이 정해져 있음 (ck_auth_token_subject)
public enum AuthTokenType {
    OWNER, // 사장님 (own_), owner
    ADMIN, // 운영자 (adm_), admin
    INQUIRY, // 고객 - 상담 (inq_), inquiry, 기기마다 따로 발급
    CUSTOMER // 고객 - 예약 조회·상담 복구 (cus_), owner + customerPhone + scope
}
