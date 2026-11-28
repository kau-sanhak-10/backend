package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.dto.PushPublicKeyResponse;
import com.ilro.backend.domain.notification.dto.PushSubscriptionRequest;
import com.ilro.backend.domain.notification.dto.PushUnsubscribeRequest;
import com.ilro.backend.domain.notification.exception.NotificationErrorCode;
import com.ilro.backend.domain.notification.repository.PushSubscriptionRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// 사장님 브라우저 Web Push 구독 (B-27, 명세 5.6, BE-14)
// 공개키는 설정값(app.push.vapid-public-key) - 비어 있으면(로컬에서 키를 안 넣음) 503 PUSH_UNAVAILABLE
// 구독 주소(endpoint)는 서버가 나중에 직접 요청을 보내는 곳 → 알려진 브라우저 푸시 서버(https)만 받음 (SSRF 방지)
// 권한 거부·미지원 브라우저는 화면이 안내만 하고 여기로 보내지 않음
@Service
public class PushSubscriptionService {

    // 브라우저별 푸시 서버 - 크롬·삼성 인터넷·오페라(FCM), 파이어폭스(Mozilla)
    static final Set<String> PUSH_HOSTS = Set.of(
            "fcm.googleapis.com", "android.googleapis.com", "updates.push.services.mozilla.com");
    // 하위 도메인이 여러 개인 곳 - 사파리(web.push.apple.com 등), 엣지(*.notify.windows.com)
    static final Set<String> PUSH_HOST_SUFFIXES = Set.of("push.apple.com", "notify.windows.com");

    // push_subscription.user_agent 칸 크기
    static final int USER_AGENT_MAX = 255;

    private final PushSubscriptionRepository subscriptionRepository;
    private final String vapidPublicKey;

    public PushSubscriptionService(PushSubscriptionRepository subscriptionRepository,
                                   @Value("${app.push.vapid-public-key}") String vapidPublicKey) {
        this.subscriptionRepository = subscriptionRepository;
        this.vapidPublicKey = vapidPublicKey;
    }

    public PushPublicKeyResponse publicKey() {
        if (vapidPublicKey == null || vapidPublicKey.isBlank()) {
            throw new BusinessException(NotificationErrorCode.PUSH_UNAVAILABLE);
        }
        return new PushPublicKeyResponse(vapidPublicKey);
    }

    // 같은 endpoint가 있으면 갱신, 다른 사장님 것이었으면 이 사장님으로 옮김 (한 문장 upsert)
    @Transactional
    public void subscribe(AuthPrincipal principal, PushSubscriptionRequest request) {
        requirePushServer(request.endpoint());
        subscriptionRepository.upsert(principal.ownerId(), request.endpoint(), request.keys().p256dh(),
                request.keys().auth(), userAgentOf(request.userAgent()));
    }

    // 이 사장님 구독만 지움 - 없거나 이미 해제했거나 다른 업체 구독이어도 그대로 200 (두 번 눌러도 같은 결과)
    @Transactional
    public void unsubscribe(AuthPrincipal principal, PushUnsubscribeRequest request) {
        subscriptionRepository.deleteByEndpointAndOwnerId(request.endpoint(), principal.ownerId());
    }

    // https + 기본 포트 + 알려진 푸시 서버만 - 아니면 400 INVALID_INPUT(endpoint)
    private static void requirePushServer(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw invalidEndpoint();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean known = PUSH_HOSTS.contains(host)
                || PUSH_HOST_SUFFIXES.stream().anyMatch(suffix -> host.endsWith("." + suffix));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || !known) {
            throw invalidEndpoint();
        }
    }

    private static BusinessException invalidEndpoint() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT,
                List.of(new FieldErrorResponse("endpoint", "브라우저 푸시 서버 주소가 아닙니다")));
    }

    // 브라우저 원본 UA는 255자를 넘을 수 있음 - 구독은 실패시키지 않고 잘라서 저장, 비었으면 null
    private static String userAgentOf(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String trimmed = userAgent.strip();
        return trimmed.length() <= USER_AGENT_MAX ? trimmed : trimmed.substring(0, USER_AGENT_MAX);
    }
}