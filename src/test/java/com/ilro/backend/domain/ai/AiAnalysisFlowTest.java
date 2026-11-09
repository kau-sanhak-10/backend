package com.ilro.backend.domain.ai;

import com.ilro.backend.domain.ai.service.AiAnalysisService;
import com.ilro.backend.domain.ai.service.AiAnalysisStore;
import com.ilro.backend.domain.inquiry.entity.HandlerMode;
import com.ilro.backend.domain.inquiry.entity.InquiryFieldSource;
import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.domain.owner.entity.WorkCategory;
import com.ilro.backend.infra.ai.AiCallResult;
import com.ilro.backend.infra.ai.AiCallResult.Outcome;
import com.ilro.backend.infra.ai.AnalyzeRequest;
import com.ilro.backend.infra.ai.AnalyzeResult;
import com.ilro.backend.infra.ai.AnalyzeResult.Evidence;
import com.ilro.backend.infra.ai.AnalyzeResult.Fact;
import com.ilro.backend.support.ScriptedAiClient;
import com.ilro.backend.support.TestAccounts;
import com.ilro.backend.support.TestAiConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AI 분석 결과 적용·재시도 (BE-05 완료 기준, 명세 6장) - 정해 둔 AI 답으로 분석 작업을 바로 부름
// 시도당 2초·전체 3초로 줄여서 확인 (운영 30초·60초와 같은 규칙)
@SpringBootTest(properties = {"app.ai.attempt-timeout=PT2S", "app.ai.total-budget=PT3S"})
@Import(TestAiConfig.class)
class AiAnalysisFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private AiAnalysisService analysisService;
    @Autowired
    private AiAnalysisStore store;
    @Autowired
    private ScriptedAiClient ai;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerPhone;

    private record Session(long inquiryId, String token) {

        String path(String suffix) {
            return "/api/v1/customer/inquiries/" + inquiryId + suffix;
        }
    }

    @BeforeEach
    void setUp() {
        ai.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerPhone = accounts.newPhone();
        ownerId = accounts.approvedOwner(ownerPhone);
        jdbc.update("INSERT INTO owner_work_type (owner_id, work_category, duration_minutes, is_active) "
                + "VALUES (?, 'REPAIR', 60, true), (?, 'INSPECTION', 60, true), (?, 'LEAK', 90, false)",
                ownerId, ownerId, ownerId);
    }

    @AfterEach
    void tearDown() {
        ai.reset();
        accounts.cleanUp();
    }

    @Test
    @DisplayName("성공 - 사실·작업·AI 답변·판단을 한 번에 저장, version 1 오름·AI 입력 순번 그대로")
    void applySuccess() throws Exception {
        Session session = startInquiry();
        long messageId = send(session, "보일러 밑에서 물이 새요");
        Map<String, Object> before = inquiry(session);
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE").fact("symptom", "보일러 밑 누수", messageId).category("REPAIR")));

        analysisService.analyze(session.inquiryId());

        AnalyzeRequest sent = ai.requests().getFirst();
        assertThat(sent.inquiryId()).isEqualTo(session.inquiryId());
        assertThat(sent.ownerId()).isEqualTo(ownerId);
        assertThat(sent.inputVersion()).isEqualTo(((Number) before.get("ai_input_seq")).longValue());
        assertThat(sent.handlerMode()).isEqualTo(HandlerMode.AI);
        assertThat(sent.business().activeWorkCategories()).containsExactly(WorkCategory.INSPECTION,
                WorkCategory.REPAIR);
        assertThat(sent.business().autoBookingEnabled()).isFalse();
        assertThat(sent.messages()).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo(messageId);
            assertThat(message.sender()).isEqualTo(MessageSender.CUSTOMER);
            assertThat(message.content()).isEqualTo("보일러 밑에서 물이 새요");
        });
        assertThat(sent.fields()).isEmpty();
        assertThat(sent.askCounts()).isEmpty();
        assertThat(sent.photos()).isEmpty();
        assertThat(ai.timeouts()).containsExactly(Duration.ofSeconds(2));

        Map<String, Object> decision = jdbc.queryForMap("SELECT id, message_id, stage, status, action, rule_reason, "
                + "decided_by, model_name, prompt_version, rule_version, intent, confidence, latency_ms, input_tokens, "
                + "output_tokens, input_version, request_id, output_raw::text AS output_raw FROM ai_decision "
                + "WHERE inquiry_id = ?", session.inquiryId());
        assertThat(decision).containsEntry("message_id", messageId)
                .containsEntry("stage", "DECIDE")
                .containsEntry("status", "SUCCESS")
                .containsEntry("action", "CHECK_SCHEDULE")
                .containsEntry("rule_reason", "DONE")
                .containsEntry("decided_by", "EXTERNAL_API")
                .containsEntry("model_name", "claude-haiku-4-5")
                .containsEntry("prompt_version", "v5.2")
                .containsEntry("rule_version", "rules@abc123")
                .containsEntry("intent", "ANSWER")
                .containsEntry("confidence", new BigDecimal("0.88"))
                .containsEntry("latency_ms", 1200)
                .containsEntry("input_tokens", 900)
                .containsEntry("output_tokens", 120)
                .containsEntry("input_version", sent.inputVersion())
                .containsEntry("request_id", sent.requestId());
        assertThat((String) decision.get("output_raw")).contains("\"success\"").contains("보일러 밑 누수");

        assertThat(jdbc.queryForMap("SELECT field_key, field_value, source, status, message_id, ai_decision_id "
                + "FROM inquiry_field WHERE inquiry_id = ? AND is_current", session.inquiryId()))
                .containsEntry("field_key", "symptom")
                .containsEntry("field_value", "보일러 밑 누수")
                .containsEntry("source", "CUSTOMER")
                .containsEntry("status", "CONFIRMED")
                .containsEntry("message_id", messageId)
                .containsEntry("ai_decision_id", decision.get("id"));
        assertThat(inquiry(session))
                .containsEntry("status", "SCHEDULING")
                .containsEntry("symptom", "보일러 밑 누수")
                .containsEntry("version", (Integer) before.get("version") + 1)
                .containsEntry("ai_input_seq", before.get("ai_input_seq"))
                .containsEntry("work_type_id", workTypeId("REPAIR"));
        assertThat(jdbc.queryForMap("SELECT delivery_status, ai_decision_id FROM message WHERE inquiry_id = ? "
                + "AND sender = 'AI'", session.inquiryId()))
                .containsEntry("delivery_status", "SENT")
                .containsEntry("ai_decision_id", decision.get("id"));
        getAs(session.token(), session.path("/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.messages", hasSize(2)))
                .andExpect(jsonPath("$.result.messages[1].sender").value("AI"))
                .andExpect(jsonPath("$.result.messages[1].content").value("확인했어요."));

        // 이미 답한 메시지 - 다시 부르지 않음. 접수정보만 바뀌어도 새로 답하지 않음
        analysisService.analyze(session.inquiryId());
        patchAs(session.token(), session.path("/fields"), "{\"version\":" + inquiry(session).get("version")
                + ",\"fields\":[{\"fieldKey\":\"manufacturer\",\"value\":\"린나이\"}]}")
                .andExpect(status().isOk());
        analysisService.analyze(session.inquiryId());
        assertThat(ai.requests()).hasSize(1);
    }

    @Test
    @DisplayName("이름·전화번호·주소는 AI 서버에 보내지 않음 - 요청 기록(input_snapshot)에도 없음")
    void noPersonalInfoInRequest() throws Exception {
        Session session = startInquiry();
        putAs(session.token(), session.path("/visit-info"), """
                {"version":0,"name":"홍길동","phone":"010-1111-2222","address":"경기 고양시 덕양구 화정로 1",
                 "addressDetail":"101동 202호","lat":37.634,"lng":126.832,"accessNote":"주차는 지하 2층",
                 "workCategory":null}""")
                .andExpect(status().isOk());
        send(session, "화면에 E1이 떠요");
        ai.then(reply(answer("COMPLETE", "DONE")));

        analysisService.analyze(session.inquiryId());

        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM ai_decision WHERE inquiry_id = ?",
                String.class, session.inquiryId());
        assertThat(snapshot).contains("화면에 E1이 떠요")
                .doesNotContain("홍길동", "01011112222", "010-1111-2222", "화정로", "101동", "주차", ownerPhone);
        // 정리 완료(COMPLETE)는 자동 예약이 아니면 사장님 확인 + 알림
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
        long inputVersion = ai.requests().getFirst().inputVersion();
        assertThat(jdbc.queryForMap("SELECT owner_id, recipient, event_type, ref_type, channel, send_status "
                + "FROM notification WHERE dedupe_key = ?", "NEEDS_CHECK:INQUIRY:" + session.inquiryId() + ":"
                + inputVersion))
                .containsEntry("owner_id", ownerId)
                .containsEntry("recipient", "OWNER")
                .containsEntry("event_type", "NEEDS_CHECK")
                .containsEntry("ref_type", "INQUIRY")
                .containsEntry("channel", "IN_APP")
                .containsEntry("send_status", "PENDING");
    }

    @Test
    @DisplayName("보완 질문 수 - 적용된 보완 질문의 대상 항목을 세어 다음 요청에 보냄, 대화는 오래된 순")
    void askCounts() throws Exception {
        Session session = startInquiry();
        long first = send(session, "보일러가 안 켜져요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("error_code", "model")
                .fact("symptom", "전원 안 켜짐", first)));
        analysisService.analyze(session.inquiryId());
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");

        send(session, "에러코드는 잘 모르겠어요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("error_code")));
        analysisService.analyze(session.inquiryId());
        send(session, "03이에요");
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE")));
        analysisService.analyze(session.inquiryId());

        List<AnalyzeRequest> requests = ai.requests();
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).askCounts()).isEmpty();
        assertThat(requests.get(1).askCounts()).containsOnly(Map.entry("error_code", 1), Map.entry("model", 1));
        assertThat(requests.get(2).askCounts()).containsOnly(Map.entry("error_code", 2), Map.entry("model", 1));
        assertThat(requests.get(1).fields()).singleElement().satisfies(field -> {
            assertThat(field.fieldKey()).isEqualTo("symptom");
            assertThat(field.source()).isEqualTo(InquiryFieldSource.CUSTOMER);
            assertThat(field.messageId()).isEqualTo(first);
        });
        assertThat(requests.get(2).messages()).extracting(AnalyzeRequest.MessageItem::sender)
                .containsExactly(MessageSender.CUSTOMER, MessageSender.AI, MessageSender.CUSTOMER, MessageSender.AI,
                        MessageSender.CUSTOMER);
        assertThat(inquiry(session)).containsEntry("status", "SCHEDULING");
    }

    @Test
    @DisplayName("분석 중 새 메시지가 오면 결과는 STALE로만 남기고 최신 입력으로 다시 분석")
    void newMessageDuringCall() throws Exception {
        Session session = startInquiry();
        long first = send(session, "물이 새요");
        ai.then(request -> {
            send(session, "아 그리고 소리도 나요");
            return ok(answer("CHECK_SCHEDULE", "DONE").fact("symptom", "오래된 결과", first).build(request));
        }).then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("model").fact("symptom", "누수·소음", first)));

        analysisService.analyze(session.inquiryId());

        List<AnalyzeRequest> requests = ai.requests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).inputVersion()).isEqualTo(requests.get(0).inputVersion() + 1);
        assertThat(requests.get(1).requestId()).isNotEqualTo(requests.get(0).requestId());
        assertThat(requests.get(1).messages()).hasSize(2);
        assertThat(decisionStatuses(session)).containsExactly("STALE", "SUCCESS");
        assertThat(jdbc.queryForList("SELECT field_value FROM inquiry_field WHERE inquiry_id = ?", String.class,
                session.inquiryId())).containsExactly("누수·소음");
        assertThat(aiMessages(session)).isEqualTo(1);
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
    }

    @Test
    @DisplayName("분석 중 사장님 응대로 바뀌면 결과를 적용하지 않고 더 부르지 않음")
    void ownerTakesOverDuringCall() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        Map<String, Object> before = inquiry(session);
        ai.then(request -> {
            jdbc.update("UPDATE inquiry SET handler_mode = 'OWNER' WHERE id = ?", session.inquiryId());
            return ok(answer("CHECK_SCHEDULE", "DONE").build(request));
        });

        analysisService.analyze(session.inquiryId());
        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(1);
        assertThat(decisionStatuses(session)).containsExactly("STALE");
        assertThat(aiMessages(session)).isZero();
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING")
                .containsEntry("version", before.get("version"));
        assertThat(notifications(session)).isZero();
    }

    @Test
    @DisplayName("분석 중 사장님이 직접 응대를 시작하면(API) 결과는 STALE, 그 뒤 고객 메시지에도 AI가 답하지 않음")
    void takeoverApiDuringCall() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        String ownerToken = accounts.ownerToken(ownerId);
        long seqBefore = ((Number) inquiry(session).get("ai_input_seq")).longValue();
        ai.then(request -> {
            try {
                postAs(ownerToken, "/api/v1/owner/inquiries/" + session.inquiryId() + "/takeover", "")
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return ok(answer("CHECK_SCHEDULE", "DONE").build(request));
        });

        analysisService.analyze(session.inquiryId());
        send(session, "언제 와요?");
        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(1);
        assertThat(decisionStatuses(session)).containsExactly("STALE");
        assertThat(aiMessages(session)).isZero();
        // 직접 응대 시작 +1, 고객 메시지 +1
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING")
                .containsEntry("ai_input_seq", seqBefore + 2);
    }

    @Test
    @DisplayName("시간 초과 뒤 두 번째 시도에서 성공 - 같은 요청 ID, 실패 기록엔 판단값 없이")
    void retryAfterTimeout() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        ai.then(failure(Outcome.TIMEOUT, true, "AI_MODEL_TIMEOUT"))
                .then(reply(answer("CHECK_SCHEDULE", "DONE")));

        analysisService.analyze(session.inquiryId());

        List<AnalyzeRequest> requests = ai.requests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).requestId()).isEqualTo(requests.get(0).requestId());
        assertThat(requests.get(1).inputVersion()).isEqualTo(requests.get(0).inputVersion());
        assertThat(ai.timeouts()).allSatisfy(timeout -> assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(2)));
        assertThat(decisionStatuses(session)).containsExactly("TIMEOUT", "SUCCESS");
        assertThat(jdbc.queryForMap("SELECT decided_by, action, model_name, error_message, request_id "
                + "FROM ai_decision WHERE inquiry_id = ? AND status = 'TIMEOUT'", session.inquiryId()))
                .containsEntry("decided_by", "EXTERNAL_API")
                .containsEntry("action", null)
                .containsEntry("model_name", null)
                .containsEntry("error_message", "AI_MODEL_TIMEOUT")
                .containsEntry("request_id", requests.get(0).requestId());
        assertThat(aiMessages(session)).isEqualTo(1);
        assertThat(inquiry(session)).containsEntry("status", "SCHEDULING");
    }

    @Test
    @DisplayName("같은 입력으로 두 번 실패하면 사장님 확인 + 알림 하나, 그 뒤로는 부르지 않음")
    void giveUpAfterTwoFailures() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        Map<String, Object> before = inquiry(session);
        ai.then(failure(Outcome.TIMEOUT, true, "AI_MODEL_TIMEOUT"))
                .then(request -> AiCallResult.failure(Outcome.INVALID_OUTPUT, true,
                        "{\"success\":false,\"code\":\"AI_OUTPUT_INVALID\",\"message\":\"x\",\"result\":null}",
                        "AI_OUTPUT_INVALID", 15));

        analysisService.analyze(session.inquiryId());
        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(2);
        assertThat(decisionStatuses(session)).containsExactly("TIMEOUT", "INVALID_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT output_raw::text FROM ai_decision WHERE inquiry_id = ? "
                + "AND status = 'INVALID_OUTPUT'", String.class, session.inquiryId())).contains("AI_OUTPUT_INVALID");
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK")
                .containsEntry("version", (Integer) before.get("version") + 1);
        assertThat(notifications(session)).isEqualTo(1);
        assertThat(aiMessages(session)).isZero();
    }

    @Test
    @DisplayName("다시 불러도 같은 실패(권한·크레딧·할당량 등)는 한 번만 부르고 사장님 확인")
    void noRetryForPermanentFailure() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        ai.then(failure(Outcome.FAILED, false, "AI_MODEL_ERROR"));

        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(1);
        assertThat(decisionStatuses(session)).containsExactly("FAILED");
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
        assertThat(notifications(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("작업이 중간에 끊겨도 같은 입력은 기록된 실패까지 세어 최대 2회 - 같은 요청 ID로 이어서")
    void attemptsSurviveInterruptedJob() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        ai.then(failure(Outcome.TIMEOUT, true, "AI_MODEL_TIMEOUT"))
                .then(request -> {
                    throw new IllegalStateException("작업 중단 흉내");
                });
        assertThatThrownBy(() -> analysisService.analyze(session.inquiryId()))
                .isInstanceOf(IllegalStateException.class);

        ai.then(failure(Outcome.TIMEOUT, true, "AI_MODEL_TIMEOUT"));
        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(3)
                .extracting(AnalyzeRequest::requestId).containsOnly(ai.requests().getFirst().requestId());
        assertThat(decisionStatuses(session)).containsExactly("TIMEOUT", "TIMEOUT");
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
    }

    @Test
    @DisplayName("전체 시간 예산을 넘기면 더 부르지 않고 사장님 확인")
    void stopWhenBudgetRunsOut() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        ai.then(request -> {
            sleep(Duration.ofMillis(1_500));
            return failure(Outcome.TIMEOUT, true, "READ_TIMEOUT").apply(request);
        });

        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests()).hasSize(1);
        assertThat(decisionStatuses(session)).containsExactly("TIMEOUT");
        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK");
    }

    @Test
    @DisplayName("규칙 위반 결과(사장님 출처 사실)는 적용하지 않고 출력 오류로 기록한 뒤 다시")
    void ruleViolationIsInvalidOutput() throws Exception {
        Session session = startInquiry();
        long messageId = send(session, "물이 새요");
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE")
                        .fact(new Fact("symptom", "사장님 값", "OWNER", "CONFIRMED", null, new Evidence(messageId, null)))))
                .then(reply(answer("CHECK_SCHEDULE", "DONE").fact("symptom", "누수", messageId)));

        analysisService.analyze(session.inquiryId());

        assertThat(decisionStatuses(session)).containsExactly("INVALID_OUTPUT", "SUCCESS");
        assertThat(jdbc.queryForMap("SELECT action, error_message, output_raw IS NOT NULL AS has_raw FROM ai_decision "
                + "WHERE inquiry_id = ? AND status = 'INVALID_OUTPUT'", session.inquiryId()))
                .containsEntry("action", null)
                .containsEntry("error_message", "RULE_VIOLATION facts[0].source·evidence")
                .containsEntry("has_raw", true);
        assertThat(jdbc.queryForList("SELECT field_value FROM inquiry_field WHERE inquiry_id = ?", String.class,
                session.inquiryId())).containsExactly("누수");
    }

    @Test
    @DisplayName("사장님이 정한 항목은 AI가 덮어쓰지 않음, 업체가 제공하지 않는 작업은 채우지 않음")
    void ownerValuesStay() throws Exception {
        Session session = startInquiry();
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'model', 'NCB-500', 'OWNER', 'CONFIRMED')", session.inquiryId());
        jdbc.update("UPDATE inquiry SET model = 'NCB-500' WHERE id = ?", session.inquiryId());
        long messageId = send(session, "모델은 NCB-700 같아요, 물도 새요");
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE").fact("model", "NCB-700", messageId)
                .fact("symptom", "누수", messageId).category("LEAK")));

        analysisService.analyze(session.inquiryId());

        assertThat(ai.requests().getFirst().fields()).singleElement().satisfies(field -> {
            assertThat(field.fieldKey()).isEqualTo("model");
            assertThat(field.source()).isEqualTo(InquiryFieldSource.OWNER);
            assertThat(field.value()).isEqualTo("NCB-500");
        });
        assertThat(jdbc.queryForList("SELECT field_key || '=' || field_value || '/' || source FROM inquiry_field "
                + "WHERE inquiry_id = ? AND is_current ORDER BY field_key", String.class, session.inquiryId()))
                .containsExactly("model=NCB-500/OWNER", "symptom=누수/CUSTOMER");
        assertThat(inquiry(session)).containsEntry("model", "NCB-500")
                .containsEntry("work_type_id", null);
    }

    @Test
    @DisplayName("사장님 값을 고객이 다르게 고친 충돌은 AI가 풀지 못함 - 사장님이 정할 때까지 그대로")
    void customerConflictStays() throws Exception {
        Session session = startInquiry();
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status, unconfirmed_reason) "
                + "VALUES (?, 'manufacturer', '귀뚜라미', 'CUSTOMER', 'UNCONFIRMED', 'CONFLICT')", session.inquiryId());
        long messageId = send(session, "경동나비엔이에요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").fact("manufacturer", "경동나비엔", messageId)));

        analysisService.analyze(session.inquiryId());

        assertThat(jdbc.queryForList("SELECT field_value || '/' || status || '/' || unconfirmed_reason "
                + "FROM inquiry_field WHERE inquiry_id = ? AND field_key = 'manufacturer' AND is_current", String.class,
                session.inquiryId())).containsExactly("귀뚜라미/UNCONFIRMED/CONFLICT");
    }

    @Test
    @DisplayName("AI가 찾은 충돌 - 그 항목은 미확인(CONFLICT), 이전 값은 이력. 고객이 다시 답하면 확인됨")
    void aiConflictIsUnconfirmed() throws Exception {
        Session session = startInquiry();
        long first = send(session, "난방이 안 돼요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").fact("symptom", "난방이 안 돼요", first)));
        analysisService.analyze(session.inquiryId());
        long second = send(session, "아니 온수가 안 나와요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("symptom")
                .conflict("symptom", candidate("난방이 안 돼요", "CUSTOMER", first),
                        candidate("온수가 안 나와요", "CUSTOMER", second))));

        analysisService.analyze(session.inquiryId());

        long decisionId = jdbc.queryForObject("SELECT max(id) FROM ai_decision WHERE inquiry_id = ?", Long.class,
                session.inquiryId());
        assertThat(jdbc.queryForMap("SELECT field_value, source, status, unconfirmed_reason, message_id, "
                + "ai_decision_id FROM inquiry_field WHERE inquiry_id = ? AND field_key = 'symptom' AND is_current",
                session.inquiryId()))
                .containsEntry("field_value", "온수가 안 나와요")
                .containsEntry("source", "CUSTOMER")
                .containsEntry("status", "UNCONFIRMED")
                .containsEntry("unconfirmed_reason", "CONFLICT")
                .containsEntry("message_id", second)
                .containsEntry("ai_decision_id", decisionId);
        assertThat(jdbc.queryForList("SELECT field_value || '/' || status FROM inquiry_field WHERE inquiry_id = ? "
                + "AND field_key = 'symptom' AND NOT is_current", String.class, session.inquiryId()))
                .containsExactly("난방이 안 돼요/CONFIRMED");
        // 고객에게 다시 묻는 중 - 사장님 확인·알림은 AI가 질문 한도에 닿을 때
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
        assertThat(notifications(session)).isZero();

        long third = send(session, "온수가 안 나와요");
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE").fact("symptom", "온수가 안 나와요", third)));
        analysisService.analyze(session.inquiryId());

        AnalyzeRequest asked = ai.requests().get(2);
        assertThat(asked.fields()).singleElement().satisfies(field -> {
            assertThat(field.fieldKey()).isEqualTo("symptom");
            assertThat(field.unconfirmedReason()).isEqualTo("CONFLICT");
        });
        assertThat(jdbc.queryForList("SELECT field_value || '/' || status FROM inquiry_field WHERE inquiry_id = ? "
                + "AND field_key = 'symptom' AND is_current", String.class, session.inquiryId()))
                .containsExactly("온수가 안 나와요/CONFIRMED");
    }

    @Test
    @DisplayName("사장님 값과 부딪힌 충돌 - 사장님 값은 그대로 (명세 6.4), 고객이 다르게 말한 내용은 판단 기록에")
    void conflictWithOwnerValue() throws Exception {
        Session session = startInquiry();
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status) "
                + "VALUES (?, 'symptom', '누수', 'OWNER', 'CONFIRMED')", session.inquiryId());
        long messageId = send(session, "물은 안 새고 온수가 안 나와요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("symptom")
                .conflict("symptom", new AnalyzeResult.Candidate("누수", "OWNER", null),
                        candidate("온수가 안 나와요", "CUSTOMER", messageId))));

        analysisService.analyze(session.inquiryId());

        assertThat(decisionStatuses(session)).containsExactly("SUCCESS");
        assertThat(jdbc.queryForList("SELECT field_value || '/' || source || '/' || status FROM inquiry_field "
                + "WHERE inquiry_id = ? AND field_key = 'symptom'", String.class, session.inquiryId()))
                .containsExactly("누수/OWNER/CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT output_raw::text LIKE '%온수가 안 나와요%' FROM ai_decision "
                + "WHERE inquiry_id = ?", Boolean.class, session.inquiryId())).isTrue();
        assertThat(inquiry(session)).containsEntry("status", "COLLECTING");
        assertThat(notifications(session)).isZero();
        assertThat(aiMessages(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("충돌 규칙 위반 - 다른 문의의 근거·후보 하나·필수 충돌 중 방문 시간 고르기는 출력 오류")
    void conflictViolations() throws Exception {
        Session session = startInquiry();
        long messageId = send(session, "온수가 안 나와요");
        ai.then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD")
                        .conflict("symptom", candidate("난방", "CUSTOMER", messageId),
                                candidate("온수", "CUSTOMER", messageId + 100_000))))
                .then(reply(answer("ASK_MORE_INFO", "MISSING_FIELD")
                        .conflict("symptom", candidate("온수", "CUSTOMER", messageId))));

        analysisService.analyze(session.inquiryId());

        assertThat(decisionStatuses(session)).containsExactly("INVALID_OUTPUT", "INVALID_OUTPUT");
        assertThat(jdbc.queryForList("SELECT error_message FROM ai_decision WHERE inquiry_id = ? ORDER BY id",
                String.class, session.inquiryId()))
                .containsExactly("RULE_VIOLATION conflicts[0].candidates", "RULE_VIOLATION conflicts[0].candidates");

        Session scheduling = startInquiry();
        long other = send(scheduling, "온수가 안 나와요");
        Function<AnalyzeRequest, AiCallResult> toSchedule = reply(answer("CHECK_SCHEDULE", "DONE")
                .conflict("symptom", candidate("난방", "CUSTOMER", other), candidate("온수", "CUSTOMER", other)));
        ai.then(toSchedule).then(toSchedule);
        analysisService.analyze(scheduling.inquiryId());
        assertThat(jdbc.queryForList("SELECT error_message FROM ai_decision WHERE inquiry_id = ? ORDER BY id",
                String.class, scheduling.inquiryId()))
                .containsExactly("RULE_VIOLATION nextAction", "RULE_VIOLATION nextAction");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry_field WHERE inquiry_id = ?", Integer.class,
                scheduling.inquiryId())).isZero();
    }

    @Test
    @DisplayName("안전 신호 - 다음 행동과 상관없이 안전 표시·사장님 확인·알림")
    void safetyFlag() throws Exception {
        Session session = startInquiry();
        send(session, "가스 냄새가 나요");
        ai.then(reply(answer("CHECK_SCHEDULE", "DONE").safety()));

        analysisService.analyze(session.inquiryId());

        assertThat(inquiry(session)).containsEntry("status", "OWNER_CHECK")
                .containsEntry("safety_flag", true);
        assertThat(notifications(session)).isEqualTo(1);
        assertThat(aiMessages(session)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} → {2}")
    @DisplayName("다음 행동별 진행 단계 - 서버 규칙(RULE) 판단엔 모델명을 저장하지 않음")
    @CsvSource({
            "ASK_MORE_INFO, MISSING_FIELD, COLLECTING, 0",
            "REQUEST_IMAGE, MISSING_FIELD, COLLECTING, 0",
            "ANSWER_SIDE_QUESTION, , COLLECTING, 0",
            "CHECK_SCHEDULE, DONE, SCHEDULING, 0",
            "COMPLETE, DONE, OWNER_CHECK, 1",
            "ESCALATE_TO_OWNER, LOW_CONFIDENCE, OWNER_CHECK, 1",
            "UNSERVICEABLE, OUT_OF_AREA, UNSERVICEABLE, 0"
    })
    void statusByAction(String action, String ruleReason, String expectedStatus, int expectedNotifications)
            throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        ai.then(reply(answer(action, ruleReason).byRule()));

        analysisService.analyze(session.inquiryId());

        assertThat(inquiry(session)).containsEntry("status", expectedStatus);
        assertThat(notifications(session)).isEqualTo(expectedNotifications);
        assertThat(jdbc.queryForMap("SELECT decided_by, model_name, action FROM ai_decision WHERE inquiry_id = ?",
                session.inquiryId()))
                .containsEntry("decided_by", "RULE")
                .containsEntry("model_name", null)
                .containsEntry("action", action);
    }

    @Test
    @DisplayName("같은 요청 ID의 결과는 한 번만 적용")
    void applyOncePerRequest() throws Exception {
        Session session = startInquiry();
        send(session, "물이 새요");
        AiAnalysisStore.Snapshot snapshot = store.prepare(session.inquiryId()).orElseThrow();
        AiCallResult call = ok(answer("ASK_MORE_INFO", "MISSING_FIELD").targets("model").build(snapshot.request()));

        assertThat(store.apply(snapshot, call)).isEqualTo(AiAnalysisStore.State.DONE);
        assertThat(store.apply(snapshot, call)).isEqualTo(AiAnalysisStore.State.DONE);

        assertThat(decisionStatuses(session)).containsExactly("SUCCESS");
        assertThat(aiMessages(session)).isEqualTo(1);
        assertThat(store.prepare(session.inquiryId())).isEmpty();
    }

    // 정해 둔 AI 답 - 기본은 판단 주체 EXTERNAL_API, 작업 미확인, 안전 신호 없음
    private static final class Answer {

        private final String action;
        private final String ruleReason;
        private final List<Fact> facts = new ArrayList<>();
        private final List<AnalyzeResult.Conflict> conflicts = new ArrayList<>();
        private List<String> targets = List.of();
        private String category;
        private boolean safety;
        private String decidedBy = "EXTERNAL_API";

        private Answer(String action, String ruleReason) {
            this.action = action;
            this.ruleReason = ruleReason;
        }

        Answer targets(String... fieldKeys) {
            this.targets = List.of(fieldKeys);
            return this;
        }

        Answer fact(String fieldKey, String value, long messageId) {
            return fact(new Fact(fieldKey, value, "CUSTOMER", "CONFIRMED", null, new Evidence(messageId, null)));
        }

        Answer fact(Fact fact) {
            facts.add(fact);
            return this;
        }

        // 실제 AI 서버처럼 facts 없이 충돌만 - 후보는 기존 값 → 새 값 순서
        Answer conflict(String fieldKey, AnalyzeResult.Candidate... candidates) {
            conflicts.add(new AnalyzeResult.Conflict(fieldKey, List.of(candidates)));
            return this;
        }

        Answer category(String workCategory) {
            this.category = workCategory;
            return this;
        }

        Answer safety() {
            this.safety = true;
            return this;
        }

        Answer byRule() {
            this.decidedBy = "RULE";
            return this;
        }

        AnalyzeResult build(AnalyzeRequest request) {
            return new AnalyzeResult(request.requestId(), request.inquiryId(), request.inputVersion(), "rules@abc123",
                    "v5.2", decidedBy, "claude-haiku-4-5", new AnalyzeResult.Usage(1200, 900, 120), facts,
                    conflicts, List.of(),
                    new AnalyzeResult.WorkCategoryDecision(category, category == null ? "UNCONFIRMED" : "CONFIRMED"),
                    new AnalyzeResult.Safety(safety, safety ? List.of("GAS_SMELL") : List.of()), "ANSWER",
                    new AnalyzeResult.NextAction(action, ruleReason, targets, "test"),
                    new AnalyzeResult.Reply("확인했어요."), new BigDecimal("0.876"));
        }
    }

    private static Answer answer(String action, String ruleReason) {
        return new Answer(action, ruleReason);
    }

    private static AnalyzeResult.Candidate candidate(String value, String source, long messageId) {
        return new AnalyzeResult.Candidate(value, source, new Evidence(messageId, null));
    }

    private Function<AnalyzeRequest, AiCallResult> reply(Answer answer) {
        return request -> ok(answer.build(request));
    }

    private AiCallResult ok(AnalyzeResult result) {
        return AiCallResult.success(result, jsonMapper.writeValueAsString(
                Map.of("success", true, "code", "OK", "message", "요청 성공", "result", result)), 30);
    }

    private static Function<AnalyzeRequest, AiCallResult> failure(Outcome outcome, boolean retryable, String code) {
        return request -> AiCallResult.failure(outcome, retryable, null, code, 30);
    }

    private Session startInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    // 고객 메시지 보내기 - AI 답을 정하는 함수 안에서도 쓰므로 검사 예외를 감쌈
    private long send(Session session, String content) {
        try {
            String body = postAs(session.token(), session.path("/messages"),
                    "{\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"content\":\"" + content + "\"}")
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return ((Number) JsonPath.read(body, "$.result.messageId")).longValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> inquiry(Session session) {
        return jdbc.queryForMap("SELECT status, version, ai_input_seq, safety_flag, symptom, model, work_type_id "
                + "FROM inquiry WHERE id = ?", session.inquiryId());
    }

    private Long workTypeId(String workCategory) {
        return jdbc.queryForObject("SELECT id FROM owner_work_type WHERE owner_id = ? AND work_category = ?",
                Long.class, ownerId, workCategory);
    }

    private List<String> decisionStatuses(Session session) {
        return jdbc.queryForList("SELECT status FROM ai_decision WHERE inquiry_id = ? ORDER BY id", String.class,
                session.inquiryId());
    }

    private int aiMessages(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ? AND sender = 'AI'",
                Integer.class, session.inquiryId());
    }

    private int notifications(Session session) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE ref_type = 'INQUIRY' AND ref_id = ? "
                + "AND event_type = 'NEEDS_CHECK'", Integer.class, session.inquiryId());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
