package com.ilro.backend.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 요청마다 번호를 붙여 그 요청의 로그를 한 번에 찾게 함 (응답 헤더 X-Request-Id로도 돌려줌)
// 클라이언트가 보낸 번호는 쓰지 않음 - 아무 문자열이나 로그에 섞일 수 있어서
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CLIENT_IP_KEY = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID_KEY, requestId);
        // 로드밸런서 뒤 실제 고객 IP (server.forward-headers-strategy: native) - 접속기록의 접속지 정보
        MDC.put(CLIENT_IP_KEY, request.getRemoteAddr());
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 톰캣 스레드는 다음 요청에 재사용되니 값이 남지 않게
            MDC.clear();
        }
    }
}