package com.ilro.backend.infra.ai;

import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.infra.ai.AiCallResult.Outcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// AI 서버 호출 - 실제 HTTP로 헤더·본문·오류 코드별 재시도 여부 확인 (명세 3.8·6.2)
class HttpAiAnalyzeClientTest {

    private static final String KEY = "test-internal-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("성공 - 내부 키 헤더·계약 칸만 보내고, 결과의 모르는 칸은 무시하고 읽음")
    void success() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        String body = """
                {"success":true,"code":"OK","message":"요청 성공","result":{
                  "requestId":"req-1","inquiryId":7,"inputVersion":3,"ruleVersion":"r1","promptVersion":"p1",
                  "decidedBy":"RULE","modelName":null,"usage":{"latencyMs":5,"inputTokens":0,"outputTokens":0},
                  "facts":[{"fieldKey":"symptom","value":"물이 새요","source":"CUSTOMER","status":"CONFIRMED",
                            "unconfirmedReason":null,"evidence":{"messageId":11,"photoId":null}}],
                  "conflicts":[],"interpretations":[{"fieldKey":"error_code","value":"E1"}],
                  "workCategory":{"value":"LEAK","status":"CONFIRMED"},"safety":{"flag":false,"reasons":[]},
                  "intent":"ANSWER","nextAction":{"action":"CHECK_SCHEDULE","ruleReason":"DONE",
                  "targetFieldKeys":[],"reason":"ok"},"reply":{"content":"방문 시간을 골라 주세요."},
                  "confidence":0.9,"addedLater":"무시"}}""";
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, body);
        });

        AiCallResult call = client(baseUrl() + "/").analyze(request(), Duration.ofSeconds(2));

        assertThat(call.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(call.rawBody()).isEqualTo(body);
        assertThat(call.result().requestId()).isEqualTo("req-1");
        assertThat(call.result().facts()).singleElement()
                .satisfies(fact -> assertThat(fact.evidence().messageId()).isEqualTo(11L));
        assertThat(call.result().nextAction().action()).isEqualTo("CHECK_SCHEDULE");
        assertThat(path.get()).isEqualTo("/internal/v1/inquiries/analyze");
        assertThat(key.get()).isEqualTo(KEY);
        JsonNode request = JSON.readTree(sent.get());
        assertThat(request.propertyNames()).containsExactlyInAnyOrder("requestId", "inquiryId", "ownerId",
                "inputVersion", "handlerMode", "business", "fields", "askCounts", "messages", "photos", "safetyFlag");
        assertThat(request.path("messages").path(0).path("content").asString()).isEqualTo("물이 새요");
        assertThat(request.path("business").path("activeWorkCategories").path(0).asString()).isEqualTo("LEAK");
    }

    @ParameterizedTest(name = "{0} {1} → {2} 재시도 {3}")
    @DisplayName("실패 응답 - 시간 초과·출력 오류·5xx만 다시, 권한·할당량·요청 형식 오류는 한 번")
    @CsvSource({
            "504, AI_MODEL_TIMEOUT, TIMEOUT, true",
            "502, AI_OUTPUT_INVALID, INVALID_OUTPUT, true",
            "502, AI_MODEL_ERROR, FAILED, false",
            "503, AI_RULES_UNAVAILABLE, FAILED, false",
            "401, UNAUTHORIZED, FAILED, false",
            "403, FORBIDDEN, FAILED, false",
            "400, INVALID_INPUT, FAILED, false",
            "500, INTERNAL_SERVER_ERROR, FAILED, true",
            "429, TOO_MANY_REQUESTS, FAILED, false"
    })
    void errorCodes(int status, String code, Outcome outcome, boolean retryable) {
        String body = "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"실패\",\"result\":null}";

        AiCallResult call = client(baseUrl(0)).toResult(status, body, 10);

        assertThat(call.outcome()).isEqualTo(outcome);
        assertThat(call.retryable()).isEqualTo(retryable);
        assertThat(call.errorCode()).isEqualTo(code);
        assertThat(call.rawBody()).isEqualTo(body);
        assertThat(call.result()).isNull();
    }

    @Test
    @DisplayName("JSON이 아닌 응답 - 게이트웨이 오류는 HTTP 상태로, 본문은 저장하지 않음")
    void nonJsonResponses() {
        HttpAiAnalyzeClient client = client(baseUrl(0));

        AiCallResult badGateway = client.toResult(502, "<html>Bad Gateway</html>", 1);
        assertThat(badGateway.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(badGateway.retryable()).isTrue();
        assertThat(badGateway.errorCode()).isEqualTo("HTTP_502");
        assertThat(badGateway.rawBody()).isNull();

        AiCallResult gatewayTimeout = client.toResult(504, "", 1);
        assertThat(gatewayTimeout.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(gatewayTimeout.retryable()).isTrue();

        AiCallResult notFound = client.toResult(404, "Not Found", 1);
        assertThat(notFound.retryable()).isFalse();
        assertThat(notFound.errorCode()).isEqualTo("HTTP_404");
    }

    @Test
    @DisplayName("200인데 읽을 수 없는 결과 - 출력 형식 오류로 다시")
    void unreadableSuccess() {
        HttpAiAnalyzeClient client = client(baseUrl(0));

        AiCallResult notJson = client.toResult(200, "ok", 1);
        assertThat(notJson.outcome()).isEqualTo(Outcome.INVALID_OUTPUT);
        assertThat(notJson.retryable()).isTrue();
        assertThat(notJson.errorCode()).isEqualTo("UNREADABLE_RESPONSE");

        AiCallResult noResult = client.toResult(200, "{\"success\":true,\"code\":\"OK\",\"result\":null}", 1);
        assertThat(noResult.errorCode()).isEqualTo("UNREADABLE_RESPONSE");

        AiCallResult wrongShape = client.toResult(200,
                "{\"success\":true,\"code\":\"OK\",\"result\":{\"facts\":\"symptom\"}}", 1);
        assertThat(wrongShape.outcome()).isEqualTo(Outcome.INVALID_OUTPUT);
        assertThat(wrongShape.errorCode()).isEqualTo("UNREADABLE_RESULT");
        assertThat(wrongShape.rawBody()).contains("facts");
    }

    @Test
    @DisplayName("시도당 대기 시간이 지나면 시간 초과 - 다시 불러 볼 만한 실패")
    void readTimeout() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });

        AiCallResult call = client(baseUrl()).analyze(request(), Duration.ofMillis(300));

        assertThat(call.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(call.retryable()).isTrue();
        assertThat(call.errorCode()).isEqualTo("READ_TIMEOUT");
        assertThat(call.latencyMs()).isBetween(250, 1_900);
    }

    @Test
    @DisplayName("AI 서버가 꺼져 있으면 연결 실패 - 다시 불러 볼 만한 실패")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        AiCallResult call = client(baseUrl(closedPort)).analyze(request(), Duration.ofSeconds(2));

        assertThat(call.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(call.retryable()).isTrue();
        assertThat(call.errorCode()).isEqualTo("CONNECTION_FAILED");
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    private String baseUrl() {
        return baseUrl(server.getAddress().getPort());
    }

    private static String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static HttpAiAnalyzeClient client(String baseUrl) {
        return new HttpAiAnalyzeClient(JSON, baseUrl, KEY, Duration.ofSeconds(1));
    }

    private static AnalyzeRequest request() {
        return new AnalyzeRequest("req-1", 7L, 3L, 3, HandlerMode.AI,
                new AnalyzeRequest.Business(List.of(WorkCategory.LEAK), false), List.of(), Map.of(),
                List.of(new AnalyzeRequest.MessageItem(11L, MessageSender.CUSTOMER, "물이 새요",
                        OffsetDateTime.of(2026, 11, 3, 10, 0, 0, 0, ZoneOffset.ofHours(9)))),
                List.of(), false);
    }
}
