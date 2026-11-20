package com.ilro.backend.global.common.util;

import com.ilro.backend.global.auth.AuthPrincipal;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// 고객정보 접근 기록 (명세 1.8) - 시각·주체·행동·대상·결과만, 값 원문은 남기지 않음
// 로거 이름 ACCESS로 남겨 CloudWatch에 365일 보관 (IF-06), 운영자 조회는 AccessLogReader (O-09, BE-19)
// DB 감사 테이블은 만들지 않음 - 이 로그 줄이 기록의 전부라 형식을 바꾸면 AccessLogEntry(읽는 쪽)도 같이 고칠 것
//   actor={주체 종류}:{ID} action={VIEW·UPDATE·DELETE} target={대상 종류}:{ID} result={OK·PARTIAL·FAILED}
@Slf4j(topic = "ACCESS")
public final class AccessLog {

    // OK: 성공 / PARTIAL: 일부만 처리됨 (처리된 범위와 실패 범위가 나뉨) / FAILED: 처리하지 못함
    public enum Result {
        OK, PARTIAL, FAILED
    }

    // 남긴 줄을 그대로 받아 가는 쪽 - 로컬·테스트의 InMemoryAccessLogReader만 등록 (서버는 CloudWatch에서 읽으므로 비어 있음)
    // 로그 설정(appender)에 붙이지 않는 이유: 스프링이 로그 설정을 다시 잡으면 붙여 둔 것이 사라짐
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private AccessLog() {
    }

    // action: VIEW·UPDATE·DELETE - 성공한 요청
    public static void record(AuthPrincipal principal, String action, String targetType, Long targetId) {
        record(principal, action, targetType, targetId, Result.OK);
    }

    // 결과까지 - 개인정보 삭제처럼 실패·일부 실패도 남겨야 하는 처리 (BE-19)
    public static void record(AuthPrincipal principal, String action, String targetType, Long targetId,
                              Result result) {
        String line = "actor=" + principal.type() + ":" + actorId(principal) + " action=" + action + " target="
                + targetType + ":" + targetId + " result=" + result;
        log.info(line);
        LISTENERS.forEach(listener -> listener.accept(line));
    }

    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
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
