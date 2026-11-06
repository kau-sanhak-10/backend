package com.ilro.backend.domain.ai;

import com.ilro.backend.domain.ai.service.AiAnalysisQueue;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고객 메시지 → 따로 스레드에서 AI 분석 → 답변 저장 (가짜 AI 서버, 명세 6.1)
// 정기 확인(sweep)은 테스트 중에 돌지 않게 간격을 길게
@SpringBootTest(properties = {"app.ai.enabled=true", "app.ai.mode=fake", "app.ai.sweep-interval=PT1H"})
class AiAnalysisQueueTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiAnalysisQueue queue;
    @Autowired
    private InquiryRepository inquiryRepository;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;

    private record Session(long inquiryId, String token) {
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("고객 메시지가 저장되면 기다리지 않고 응답, AI 답변은 따로 저장됨")
    void answersAfterCommit() throws Exception {
        Session session = startInquiry();
        long inquiryId = session.inquiryId();

        mockMvc.perform(post("/api/v1/customer/inquiries/" + inquiryId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMsgId\":\"q-1\",\"content\":\"보일러에서 물이 새요\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.aiStatus").value("PROCESSING"));

        assertThat(waitForAiReply(inquiryId)).startsWith("[가짜 응답] ");
        assertThat(jdbc.queryForMap("SELECT status, decided_by, model_name FROM ai_decision WHERE inquiry_id = ?",
                inquiryId))
                .containsEntry("status", "SUCCESS")
                .containsEntry("decided_by", "RULE")
                .containsEntry("model_name", null);
        assertThat(jdbc.queryForObject("SELECT symptom FROM inquiry WHERE id = ?", String.class, inquiryId))
                .isEqualTo("보일러에서 물이 새요");
    }

    @Test
    @DisplayName("놓친 분석 찾기 - 90초 넘게 답이 없는 24시간 안의 고객 메시지만, 다시 요청하면 답함")
    void findsMissedMessages() throws Exception {
        long missed = startInquiry().inquiryId();
        long fresh = startInquiry().inquiryId();
        long tooOld = startInquiry().inquiryId();
        long answered = startInquiry().inquiryId();
        customerMessage(missed, "5 minutes");
        customerMessage(fresh, "10 seconds");
        customerMessage(tooOld, "2 days");
        long answeredMessage = customerMessage(answered, "5 minutes");
        jdbc.update("INSERT INTO ai_decision (inquiry_id, message_id, stage, action, decided_by, status) "
                + "VALUES (?, ?, 'DECIDE', 'ASK_MORE_INFO', 'RULE', 'SUCCESS')", answered, answeredMessage);

        Instant now = Instant.now();
        assertThat(inquiryRepository.findWaitingForAi(now.minus(Duration.ofHours(24)), now.minusSeconds(90), 50))
                .contains(missed)
                .doesNotContain(fresh, tooOld, answered);

        queue.request(missed);
        assertThat(waitForAiReply(missed)).startsWith("[가짜 응답] ");
        assertThat(inquiryRepository.findWaitingForAi(now.minus(Duration.ofHours(24)), now.minusSeconds(90), 50))
                .doesNotContain(missed);
    }

    private Session startInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    // 분석 요청 없이 저장된 고객 메시지 (서버가 꺼져 놓친 경우 흉내)
    private long customerMessage(long inquiryId, String ago) {
        return jdbc.queryForObject("INSERT INTO message (inquiry_id, sender, content, delivery_status, created_at) "
                + "VALUES (?, 'CUSTOMER', '물이 새요', 'SENT', CURRENT_TIMESTAMP - CAST(? AS interval)) RETURNING id",
                Long.class, inquiryId, ago);
    }

    private String waitForAiReply(long inquiryId) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(until)) {
            Map<String, Object> reply = jdbc.queryForList("SELECT content FROM message WHERE inquiry_id = ? "
                    + "AND sender = 'AI'", inquiryId).stream().findFirst().orElse(null);
            if (reply != null) {
                return (String) reply.get("content");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("AI 답변이 10초 안에 저장되지 않음 inquiryId=" + inquiryId);
    }
}
