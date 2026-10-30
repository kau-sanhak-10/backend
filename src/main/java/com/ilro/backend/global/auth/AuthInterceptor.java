package com.ilro.backend.global.auth;

import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.OwnerAccessPolicy;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

// Authorization: Bearer {토큰} 확인 (명세 1.5) - 경로마다 쓸 수 있는 토큰 종류를 WebConfig에서 정함
// 토큰 없음·철회 → 401 UNAUTHORIZED, 만료 → 401 TOKEN_EXPIRED, 다른 종류 토큰 → 403 FORBIDDEN
// 사장님 토큰은 요청마다 업체가 아직 승인·이용 가능 상태인지도 확인
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = AuthPrincipal.class.getName();
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService authTokenService;
    private final OwnerAccessPolicy ownerAccessPolicy;
    private final Set<AuthTokenType> allowedTypes;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 사전 요청은 토큰 없이 옴
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        AuthPrincipal principal = authTokenService.authenticate(bearerToken(request));
        if (!allowedTypes.contains(principal.type())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (principal.type() == AuthTokenType.OWNER) {
            ownerAccessPolicy.check(principal.ownerId());
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        return true;
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
