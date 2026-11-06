package com.ilro.backend.infra.ai;

import com.ilro.backend.infra.ai.AiCallResult.Outcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

// 실제 AI 서버(FastAPI) 호출 - 헤더 X-Internal-Key로 서로 확인 (명세 1.1·6.2)
// 요청·응답 본문과 내부 키는 로그에 남기지 않음
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "http")
public class HttpAiAnalyzeClient implements AiAnalyzeClient {

    static final String ANALYZE_PATH = "/internal/v1/inquiries/analyze";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI analyzeUri;
    private final String internalKey;

    public HttpAiAnalyzeClient(JsonMapper jsonMapper,
                               @Value("${app.ai.base-url}") String baseUrl,
                               @Value("${app.ai.internal-key}") String internalKey,
                               @Value("${app.ai.connect-timeout:3s}") Duration connectTimeout) {
        // 결과에 칸이 늘어나도 읽을 수 있게 (모르는 칸은 무시)
        this.jsonMapper = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.analyzeUri = URI.create(baseUrl.replaceAll("/+$", "") + ANALYZE_PATH);
        this.internalKey = internalKey;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public AiCallResult analyze(AnalyzeRequest request, Duration timeout) {
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(analyzeUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(request),
                            StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            return AiCallResult.failure(Outcome.FAILED, true, null, "CONNECT_TIMEOUT", elapsedMs(started));
        } catch (HttpTimeoutException e) {
            return AiCallResult.failure(Outcome.TIMEOUT, true, null, "READ_TIMEOUT", elapsedMs(started));
        } catch (IOException e) {
            log.warn("AI 서버 연결 실패 inquiryId={}: {}", request.inquiryId(), e.getClass().getSimpleName());
            return AiCallResult.failure(Outcome.FAILED, true, null, "CONNECTION_FAILED", elapsedMs(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AiCallResult.failure(Outcome.FAILED, false, null, "INTERRUPTED", elapsedMs(started));
        }
        return toResult(response.statusCode(), response.body(), elapsedMs(started));
    }

    // 공통 응답 {success, code, message, result} - 실패는 AI 서버 오류 코드(명세 3.8)로 나눔
    AiCallResult toResult(int status, String body, int latencyMs) {
        JsonNode root = readTree(body);
        String raw = root == null ? null : body;
        if (status / 100 == 2) {
            if (root == null || !root.path("success").asBoolean(false) || !root.path("result").isObject()) {
                return AiCallResult.failure(Outcome.INVALID_OUTPUT, true, raw, "UNREADABLE_RESPONSE", latencyMs);
            }
            try {
                return AiCallResult.success(jsonMapper.treeToValue(root.path("result"), AnalyzeResult.class), body,
                        latencyMs);
            } catch (JacksonException e) {
                return AiCallResult.failure(Outcome.INVALID_OUTPUT, true, raw, "UNREADABLE_RESULT", latencyMs);
            }
        }
        JsonNode code = root == null ? null : root.path("code");
        String errorCode = code != null && code.isString() ? code.asString() : "HTTP_" + status;
        return switch (errorCode) {
            case "AI_MODEL_TIMEOUT" -> AiCallResult.failure(Outcome.TIMEOUT, true, raw, errorCode, latencyMs);
            case "AI_OUTPUT_INVALID" -> AiCallResult.failure(Outcome.INVALID_OUTPUT, true, raw, errorCode, latencyMs);
            // 모델 API 오류(AI_MODEL_ERROR)는 권한·크레딧·할당량 오류와 구분되지 않아 다시 부르지 않음
            // 규칙 파일·내부 키·요청 형식 오류도 다시 불러도 같음
            case "AI_MODEL_ERROR", "AI_RULES_UNAVAILABLE", "UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT" ->
                    AiCallResult.failure(Outcome.FAILED, false, raw, errorCode, latencyMs);
            // 코드 없는 게이트웨이 오류 등 - 5xx만 다시
            default -> AiCallResult.failure(status == 504 ? Outcome.TIMEOUT : Outcome.FAILED, status >= 500, raw,
                    errorCode, latencyMs);
        };
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static int elapsedMs(long started) {
        return (int) Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
