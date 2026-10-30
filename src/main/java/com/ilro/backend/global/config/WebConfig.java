package com.ilro.backend.global.config;

import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.OwnerAccessPolicy;
import com.ilro.backend.global.auth.AuthInterceptor;
import com.ilro.backend.global.auth.AuthPrincipalArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.List;

// 경로별 토큰 종류 (명세 1.1) - 다른 업체·고객 데이터는 각 서비스가 토큰의 업체·문의로만 조회해서 막음
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthTokenService authTokenService;
    private final OwnerAccessPolicy ownerAccessPolicy;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor(EnumSet.of(AuthTokenType.OWNER)))
                .addPathPatterns("/api/v1/owner/**");
        registry.addInterceptor(interceptor(EnumSet.of(AuthTokenType.ADMIN)))
                .addPathPatterns("/api/v1/admin/**");
        // 문의 토큰·고객 토큰의 세부 범위(그 문의만, 그 번호만)는 BE-18 서비스에서 확인
        registry.addInterceptor(interceptor(EnumSet.of(AuthTokenType.INQUIRY, AuthTokenType.CUSTOMER)))
                .addPathPatterns("/api/v1/customer/**");
        registry.addInterceptor(interceptor(EnumSet.allOf(AuthTokenType.class)))
                .addPathPatterns("/api/v1/auth/logout");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthPrincipalArgumentResolver());
    }

    private AuthInterceptor interceptor(EnumSet<AuthTokenType> allowedTypes) {
        return new AuthInterceptor(authTokenService, ownerAccessPolicy, allowedTypes);
    }
}
