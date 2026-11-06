package com.ilro.backend.global.common.util;

import com.ilro.backend.global.auth.AuthPrincipal;
import lombok.extern.slf4j.Slf4j;

// 고객정보 접근 기록 (명세 1.8) - 시각·주체·행동·대상만, 값 원문은 남기지 않음
// 로거 이름 ACCESS로 따로 모아 CloudWatch에 365일 보관 (IF-06, 보관 설정은 BE-19)
@Slf4j(topic = "ACCESS")
public final class AccessLog {

    private AccessLog() {
    }

    // action: VIEW·UPDATE·DELETE, 성공한 요청만 기록
    public static void record(AuthPrincipal principal, String action, String targetType, Long targetId) {
        log.info("actor={}:{} action={} target={}:{} result=OK", principal.type(), actorId(principal), action,
                targetType, targetId);
    }

    private static Long actorId(AuthPrincipal principal) {
        return switch (principal.type()) {
            case OWNER -> principal.ownerId();
            case ADMIN -> principal.adminId();
            case INQUIRY -> principal.inquiryId();
            case CUSTOMER -> principal.tokenId();
        };
    }
}
