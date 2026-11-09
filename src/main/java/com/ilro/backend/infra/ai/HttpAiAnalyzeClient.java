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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

// 실제 AI 서버(FastAPI) 호출 - 헤더 X-Internal-Key로 서로 확인 (명세 1.1·6.2)
// 요청·응답 본문과 내부 키는 로그에 남기지 않음
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "http")
public class HttpAiAnalyzeClient implements AiAnalyzeClient {

    static final String ANALYZE_PATH = "/internal/v1/inquiries/analyze";
    private static final long SUPERSEDED_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

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

    // 시도당 대기 시간(timeout)은 응답 본문을 다 받을 때까지 - 요청의 timeout은 응답 헤더까지만 막으므로
    // 헤더 뒤 본문이 멈춰도(프록시·네트워크 끊김) 작업 스레드가 묶이지 않게 전체를 이 시간만 기다리고 취소함
    @Override
    public AiCallResult analyze(AnalyzeRequest request, Duration timeout) {
        return analyze(request, timeout, null);
    }

    @Override
    public AiCallResult analyze(AnalyzeRequest request, Duration timeout, BooleanSupplier superseded) {
        long started = System.nanoTime();
        if (superseded != null && superseded.getAsBoolean()) {
            return superseded(started);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(analyzeUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Internal-Key", internalKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(request),
                        StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> pending =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            while (true) {
                if (superseded != null && superseded.getAsBoolean()) {
                    pending.cancel(true);
                    return superseded(started);
                }
                long left = timeout.toNanos() - (System.nanoTime() - started);
                if (left <= 0) {
                    pending.cancel(true);
                    return AiCallResult.failure(Outcome.TIMEOUT, true, null, "READ_TIMEOUT", elapsedMs(started));
                }
                try {
                    long wait = superseded == null ? left : Math.min(left, SUPERSEDED_CHECK_NANOS);
                    HttpResponse<String> response = pending.get(wait, TimeUnit.NANOSECONDS);
                    return toResult(response.statusCode(), response.body(), elapsedMs(started));
                } catch (TimeoutException ignored) {
                    // 사진 대기 중에만 새 텍스트가 들어왔는지 짧은 간격으로 확인한다.
                }
            }
        } catch (ExecutionException e) {
            return failure(request, e.getCause(), started);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return AiCallResult.failure(Outcome.FAILED, false, null, "INTERRUPTED", elapsedMs(started));
        }
    }

    private static AiCallResult superseded(long started) {
        return AiCallResult.failure(Outcome.SUPERSEDED, false, null, "INPUT_SUPERSEDED", elapsedMs(started));
    }

    private static AiCallResult failure(AnalyzeRequest request, Throwable cause, long started) {
        if (cause instanceof HttpConnectTimeoutException) {
            return AiCallResult.failure(Outcome.FAILED, true, null, "CONNECT_TIMEOUT", elapsedMs(started));
        }
        if (cause instanceof HttpTimeoutException) {
            return AiCallResult.failure(Outcome.TIMEOUT, true, null, "READ_TIMEOUT", elapsedMs(started));
        }
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        log.warn("AI 서버 연결 실패 inquiryId={}: {}", request.inquiryId(), type);
        return AiCallResult.failure(Outcome.FAILED, true, null, "CONNECTION_FAILED", elapsedMs(started));
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
