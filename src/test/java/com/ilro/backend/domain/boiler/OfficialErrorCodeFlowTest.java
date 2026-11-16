package com.ilro.backend.domain.boiler;

import com.ilro.backend.domain.boiler.service.OfficialErrorCodeLookup;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 오류코드 공식 설명 (AI-14) - V4로 넣은 귀뚜라미 매뉴얼 자료와 사장님 문의 상세(B-13)의 officialErrorCode
@SpringBootTest
class OfficialErrorCodeFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                         {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final String OFFICIAL = "$.result.officialErrorCode";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private long ownerId;
    private String ownerToken;

    private record Session(long inquiryId, String token) {

        String path(String suffix) {
            return "/api/v1/customer/inquiries/" + inquiryId + suffix;
        }

        String ownerPath() {
            return "/api/v1/owner/inquiries/" + inquiryId;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ownerId = accounts.approvedOwner(accounts.newPhone());
        ownerToken = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    // ---------- 매뉴얼 자료 ----------

    @Test
    @DisplayName("매뉴얼 자료 - 귀뚜라미 5권, 문서 코드, 매뉴얼별 오류코드·모델 수와 오류코드 쪽")
    void seededManuals() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.product_name, m.document_version,
                       (SELECT count(*) FROM official_manual_error_code e WHERE e.manual_id = m.id) AS codes,
                       (SELECT count(*) FROM official_manual_model mm WHERE mm.manual_id = m.id) AS models,
                       (SELECT min(page) FROM official_manual_error_code e WHERE e.manual_id = m.id) AS first_page,
                       (SELECT max(page) FROM official_manual_error_code e WHERE e.manual_id = m.id) AS last_page
                FROM official_manual m WHERE m.manufacturer = '귀뚜라미' ORDER BY m.id""");

        // 모델 수: 규격표 모델명 × (제품 이름·줄 이름) + 제품 이름만 1 ((C)(J)는 제품 이름만 없음)
        assertThat(rows).extracting(row -> row.get("product_name"), row -> row.get("document_version"),
                        row -> ((Number) row.get("codes")).intValue(), row -> ((Number) row.get("models")).intValue(),
                        row -> row.get("first_page"), row -> row.get("last_page"))
                .containsExactly(
                        tuple("트윈알파 eco E11 가스보일러", "S 250114", 18, 7 * 2 + 1, 24, 25),
                        tuple("트윈알파 eco L11 가스보일러", "S 260914", 18, 56 * 2 + 1, 27, 28),
                        tuple("거꾸로 ECO 콘덴싱(C)(J) 가스보일러", "S 260911", 20, 10, 24, 25),
                        tuple("거꾸로 ECO 콘덴싱 S20 가스보일러", "S 260911", 20, 6 * 2 + 1, 25, 26),
                        tuple("거꾸로 ECO 콘덴싱 L20 가스보일러", "S 260911", 20, 44 * 2 + 1, 27, 28));
        // 거꾸로 ECO 콘덴싱에만 있는 코드, 매뉴얼별로 띄어쓰기가 다른 문장
        assertThat(jdbc.queryForList("""
                SELECT m.product_name || ' ' || e.code || ' ' || e.page || ' ' || coalesce(e.description, '-')
                FROM official_manual_error_code e JOIN official_manual m ON m.id = e.manual_id
                WHERE e.code IN ('E492', 'E621') ORDER BY m.id, e.code""", String.class))
                .containsExactly(
                        "트윈알파 eco E11 가스보일러 E621 25 지진감지기가 작동된 상태입니다.(지진발생 시 작동)",
                        "트윈알파 eco L11 가스보일러 E621 28 지진감지기가 작동된 상태입니다.(지진발생 시 작동)",
                        "거꾸로 ECO 콘덴싱(C)(J) 가스보일러 E492 25 -",
                        "거꾸로 ECO 콘덴싱(C)(J) 가스보일러 E621 25 지진 감지기가 작동된 상태입니다.(지진 발생 시 작동)",
                        "거꾸로 ECO 콘덴싱 S20 가스보일러 E492 26 -",
                        "거꾸로 ECO 콘덴싱 S20 가스보일러 E621 26 지진감지기가 작동된 상태입니다.(지진발생 시 작동)",
                        "거꾸로 ECO 콘덴싱 L20 가스보일러 E492 28 -",
                        "거꾸로 ECO 콘덴싱 L20 가스보일러 E621 28 지진감지기가 작동된 상태입니다.(지진발생 시 작동)");
    }

    @Test
    @DisplayName("자료의 모델 키·제조사·오류코드는 조회 규칙으로 바꾼 값과 같음 - 규칙을 바꾸면 자료도 같이 고침")
    void seededKeysFollowLookupRules() {
        List<Map<String, Object>> models = jdbc.queryForList(
                "SELECT manufacturer, model_name, model_key FROM official_manual_model");
        assertThat(models).isNotEmpty().allSatisfy(row -> assertThat(OfficialErrorCodeLookup.modelKey(
                (String) row.get("manufacturer"), (String) row.get("model_name")))
                .as((String) row.get("model_name")).isEqualTo(row.get("model_key")));
        assertThat(jdbc.queryForList("SELECT manufacturer FROM official_manual", String.class))
                .allSatisfy(maker -> assertThat(OfficialErrorCodeLookup.manufacturerKey(maker)).isEqualTo(maker));
        assertThat(jdbc.queryForList("SELECT DISTINCT code FROM official_manual_error_code", String.class))
                .allSatisfy(code -> assertThat(OfficialErrorCodeLookup.errorCodeKey(code)).isEqualTo(code));
    }

    // ---------- 사장님 문의 상세 ----------

    @Test
    @DisplayName("셋 다 맞으면 매뉴얼의 오류 이름·설명·출처·쪽 - 띄어쓰기·대소문자·제품 이름 꼴·숫자만 쓴 코드도 같게 봄")
    void matched() throws Exception {
        Session session = startInquiry();
        ownerSets(session, field("manufacturer", "귀뚜라미"), field("model", "l20 22hfn"), field("error_code", "297"));

        getAs(ownerToken, session.ownerPath())
                .andExpect(status().isOk())
                .andExpect(jsonPath(OFFICIAL + ".status").value("MATCHED"))
                .andExpect(jsonPath(OFFICIAL + ".reason").value(nullValue()))
                .andExpect(jsonPath(OFFICIAL + ".errorCode").value("E297"))
                .andExpect(jsonPath(OFFICIAL + ".modelName").value("L20-22HFN"))
                .andExpect(jsonPath(OFFICIAL + ".title").value("가스누설이상"))
                .andExpect(jsonPath(OFFICIAL + ".description").value(startsWith("보일러 내부에서 가스가 누설되고")))
                .andExpect(jsonPath(OFFICIAL + ".manual.productName").value("거꾸로 ECO 콘덴싱 L20 가스보일러"))
                .andExpect(jsonPath(OFFICIAL + ".manual.documentName").value("사용·설치 설명서(품질보증서 재중)"))
                .andExpect(jsonPath(OFFICIAL + ".manual.documentVersion").value("S 260911"))
                .andExpect(jsonPath(OFFICIAL + ".manual.page").value(27))
                .andExpect(jsonPath(OFFICIAL + ".manual.sourceUrl").value("https://krb.co.kr/download"))
                .andExpect(jsonPath(OFFICIAL + ".manual.fileUrl").value(containsString("file_srl=27788")))
                .andExpect(jsonPath(OFFICIAL + ".manual.checkedOn").value("2026-11-16"))
                // 항목 값은 사실 칸에 그대로 - 공식 설명이 값을 바꾸지 않음
                .andExpect(jsonPath("$.result.fields[3].value").value("297"));

        ownerSets(session, field("model", "귀뚜라미 트윈알파 에코 E11 가스보일러"), field("error_code", "e-001"));
        getAs(ownerToken, session.ownerPath())
                .andExpect(jsonPath(OFFICIAL + ".status").value("MATCHED"))
                .andExpect(jsonPath(OFFICIAL + ".errorCode").value("E001"))
                .andExpect(jsonPath(OFFICIAL + ".modelName").value("트윈알파 eco E11"))
                .andExpect(jsonPath(OFFICIAL + ".title").value("점화에러"))
                .andExpect(jsonPath(OFFICIAL + ".manual.documentVersion").value("S 250114"))
                .andExpect(jsonPath(OFFICIAL + ".manual.page").value(24));

        // 조치 단계만 있고 설명 문장이 없는 코드 - 설명은 null, 이름·출처는 보여 줌
        ownerSets(session, field("model", "거꾸로 ECO 콘덴싱-27H(J)"), field("error_code", "E492"));
        getAs(ownerToken, session.ownerPath())
                .andExpect(jsonPath(OFFICIAL + ".status").value("MATCHED"))
                .andExpect(jsonPath(OFFICIAL + ".modelName").value("거꾸로 ECO 콘덴싱-27H(J)"))
                .andExpect(jsonPath(OFFICIAL + ".title").value("배관누수에러"))
                .andExpect(jsonPath(OFFICIAL + ".description").value(nullValue()))
                .andExpect(jsonPath(OFFICIAL + ".manual.page").value(25));
    }

    @Test
    @DisplayName("오류코드 값이 없는 문의는 공식 설명 칸이 없음 (null)")
    void noErrorCode() throws Exception {
        Session session = startInquiry();
        getAs(ownerToken, session.ownerPath())
                .andExpect(status().isOk())
                .andExpect(jsonPath(OFFICIAL).value(nullValue()));

        ownerSets(session, field("manufacturer", "귀뚜라미"), field("model", "L20-22HFN"));
        getAs(ownerToken, session.ownerPath()).andExpect(jsonPath(OFFICIAL).value(nullValue()));
    }

    @Test
    @DisplayName("확인되지 않은 값(고객이 모름·충돌)이 있으면 공식 설명 미확인 - 문의 상태·version은 그대로")
    void fieldNotConfirmed() throws Exception {
        // 모델 값 없음
        Session noModel = startInquiry();
        ownerSets(noModel, field("manufacturer", "귀뚜라미"), field("error_code", "E001"));
        assertUnconfirmed(noModel, "FIELD_NOT_CONFIRMED");

        // 모델 - 고객이 모름
        Session session = startInquiry();
        patchAs(session.token(), session.path("/fields"), "{\"version\":" + version(session)
                + ",\"fields\":[{\"fieldKey\":\"model\",\"unsure\":true}]}").andExpect(status().isOk());
        ownerSets(session, field("manufacturer", "귀뚜라미"), field("error_code", "E001"));
        assertUnconfirmed(session, "FIELD_NOT_CONFIRMED");

        // 모델은 확인됐지만 오류코드가 충돌로 미확인 - 값이 있어도 확인 전이면 미확인
        ownerSets(session, field("model", "E11-13HFN"));
        jdbc.update("UPDATE inquiry_field SET is_current = false WHERE inquiry_id = ? AND field_key = 'error_code'",
                session.inquiryId());
        jdbc.update("INSERT INTO inquiry_field (inquiry_id, field_key, field_value, source, status, unconfirmed_reason) "
                + "VALUES (?, 'error_code', 'E001', 'CUSTOMER', 'UNCONFIRMED', 'CONFLICT')", session.inquiryId());
        Map<String, Object> before = inquiry(session);
        assertUnconfirmed(session, "FIELD_NOT_CONFIRMED");
        assertThat(inquiry(session)).isEqualTo(before);
    }

    @Test
    @DisplayName("매뉴얼이 없는 제조사·모델(다른 줄·줄 이름만·규격표에 없는 모델), 매뉴얼에 없는 코드(구형 두 자리)는 미확인")
    void notInManual() throws Exception {
        Session session = startInquiry();
        // ECO가 없는 다른 줄
        ownerSets(session, field("manufacturer", "귀뚜라미"), field("model", "거꾸로 콘덴싱 L20"),
                field("error_code", "E001"));
        assertUnconfirmed(session, "MODEL_NOT_FOUND");
        // 줄 이름만
        ownerSets(session, field("model", "L20"));
        assertUnconfirmed(session, "MODEL_NOT_FOUND");
        // 규격표에 없는 모델 (L20 36은 P(LPG)가 없음)
        ownerSets(session, field("model", "L20-36HFP"));
        assertUnconfirmed(session, "MODEL_NOT_FOUND");
        // 매뉴얼이 없는 제조사
        ownerSets(session, field("manufacturer", "경동나비엔"), field("model", "L20-22HFN"));
        assertUnconfirmed(session, "MODEL_NOT_FOUND");

        // E492는 거꾸로 ECO 콘덴싱에만 있음
        ownerSets(session, field("manufacturer", "귀뚜라미"), field("model", "트윈알파 eco E11"),
                field("error_code", "E492"));
        assertUnconfirmed(session, "CODE_NOT_FOUND");
        // 구형 두 자리 코드는 E001로 보지 않음
        ownerSets(session, field("error_code", "01"));
        assertUnconfirmed(session, "CODE_NOT_FOUND");
    }

    // ---------- 도움 함수 ----------

    private void assertUnconfirmed(Session session, String reason) throws Exception {
        getAs(ownerToken, session.ownerPath())
                .andExpect(status().isOk())
                .andExpect(jsonPath(OFFICIAL + ".status").value("UNCONFIRMED"))
                .andExpect(jsonPath(OFFICIAL + ".reason").value(reason))
                .andExpect(jsonPath(OFFICIAL + ".errorCode").value(nullValue()))
                .andExpect(jsonPath(OFFICIAL + ".title").value(nullValue()))
                .andExpect(jsonPath(OFFICIAL + ".description").value(nullValue()))
                .andExpect(jsonPath(OFFICIAL + ".manual").value(nullValue()));
    }

    private Session startInquiry() throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + accounts.linkToken(ownerId) + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(ALL_CONSENTS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    // 사장님 항목 수정 (B-14) - 출처 OWNER·확인됨
    private void ownerSets(Session session, String... fields) throws Exception {
        patchAs(ownerToken, session.ownerPath(),
                "{\"version\":" + version(session) + ",\"fields\":[" + String.join(",", fields) + "]}")
                .andExpect(status().isOk());
    }

    private int version(Session session) {
        return jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, session.inquiryId());
    }

    private Map<String, Object> inquiry(Session session) {
        return jdbc.queryForMap("SELECT status, handler_mode, version, ai_input_seq FROM inquiry WHERE id = ?",
                session.inquiryId());
    }

    private static String field(String fieldKey, String value) {
        return "{\"fieldKey\":\"" + fieldKey + "\",\"value\":\"" + value + "\"}";
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions patchAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
