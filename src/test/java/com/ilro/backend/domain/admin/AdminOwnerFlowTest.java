package com.ilro.backend.domain.admin;

import com.ilro.backend.support.CapturingSmsSender;
import com.ilro.backend.support.TestAccounts;
import com.ilro.backend.support.TestSmsConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 운영 업체·계정 관리 (O-01·O-02·O-03, 명세 4.8·5.8, BE-17)
// 등록 → 심사(승인·거절) → 운영 상태 → 로그인 번호 변경. 어떤 변경도 그 업체의 예약·고객을 지우지 않음
// 실제로 저장하고 끝나면 지움 (문자 발송 기록·토큰 철회가 오류 응답 뒤에도 남는지 봐야 해서)
@SpringBootTest
@Import(TestSmsConfig.class)
class AdminOwnerFlowTest {

    private static final String VERIFICATIONS = "/api/v1/auth/verifications";
    private static final String OWNERS = "/api/v1/admin/owners";
    private static final String OWNER_ME = "/api/v1/owner/me";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CapturingSmsSender smsSender;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String ip;
    private long staffId;
    private String staff;
    private String superAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        ip = TestAccounts.newIp();
        staffId = accounts.admin(accounts.newPhone());
        staff = accounts.adminToken(staffId, "STAFF");
        superAdmin = accounts.adminToken(accounts.admin(accounts.newPhone()), "SUPER");
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("사장님 번호 확인(OWNER_SIGNUP) - 운영자 토큰으로만 보내고, 확인해도 토큰·업체 권한은 생기지 않음")
    void signupVerification() throws Exception {
        String phone = accounts.newPhone();
        // 토큰 없음 401, 사장님 토큰 403 - 문자 없음
        send(null, "OWNER_SIGNUP", phone)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        String ownerToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        send(ownerToken, "OWNER_SIGNUP", phone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(smsSender.hasSentTo(phone)).isFalse();

        // 이미 어느 업체의 로그인 번호면 문자 없이 409
        String taken = accounts.newPhone();
        accounts.approvedOwner(taken);
        send(staff, "OWNER_SIGNUP", taken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_PHONE_DUPLICATED"));
        assertThat(smsSender.hasSentTo(taken)).isFalse();

        long verificationId = sendSignup(staff, phone);
        assertThat(smsSender.hasSentTo(phone)).isTrue();
        confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.purpose").value("OWNER_SIGNUP"))
                .andExpect(jsonPath("$.result.verifiedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.accessToken").doesNotExist())
                .andExpect(jsonPath("$.result.owner").doesNotExist());
        // 번호 확인만으로는 업체가 생기지 않고, 그 번호로 로그인 문자도 받을 수 없음
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner WHERE phone = ?", Integer.class, phone)).isZero();
        send(null, "OWNER_LOGIN", phone)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_REGISTERED"));
    }

    @Test
    @DisplayName("등록 - 201 승인 대기·설정 중·자동 예약 꺼짐, 하이픈 번호도 받고 접수 링크는 아직 죽어 있음")
    void registerOwner() throws Exception {
        String phone = accounts.newPhone();
        String hyphenated = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
        long verificationId = verifiedSignup(phone);

        String body = register(staff, registerBody("  덕구보일러 ", hyphenated, verificationId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.businessName").value("덕구보일러"))
                .andExpect(jsonPath("$.result.ownerName").value("김사장"))
                .andExpect(jsonPath("$.result.phone").value(phone))
                .andExpect(jsonPath("$.result.contactPhone").value("0311234567"))
                .andExpect(jsonPath("$.result.address").value("경기 고양시 덕양구 화정로 56"))
                .andExpect(jsonPath("$.result.serviceDescription").value("보일러 점검·수리·교체"))
                .andExpect(jsonPath("$.result.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.result.status").value("READY"))
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(false))
                .andExpect(jsonPath("$.result.intakeLinkActive").value(false))
                .andExpect(jsonPath("$.result.reviewedBy").value(nullValue()))
                .andExpect(jsonPath("$.result.reviewedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.createdAt").value(endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        long ownerId = ((Number) JsonPath.read(body, "$.result.ownerId")).longValue();
        accounts.trackOwner(ownerId);

        Map<String, Object> saved = owner(ownerId);
        assertThat(saved).containsEntry("business_name", "덕구보일러").containsEntry("phone", phone)
                .containsEntry("approval_status", "PENDING").containsEntry("status", "READY")
                .containsEntry("auto_booking_enabled", false).containsEntry("lat", null)
                .containsEntry("reviewed_by", null);
        assertThat((String) saved.get("link_token")).isNotBlank();
        // 승인 전에는 그 번호로 로그인 문자를 받을 수 없고, 접수 링크도 없는 링크
        send(null, "OWNER_LOGIN", phone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_APPROVAL_PENDING"));
        mockMvc.perform(get("/api/v1/public/intake/" + saved.get("link_token")))
                .andExpect(status().isNotFound());

        // 같은 로그인 번호로 다시 등록 - 409
        register(staff, registerBody("다른보일러", phone, verificationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_PHONE_DUPLICATED"));
    }

    @Test
    @DisplayName("등록 거절 - 필수 값·번호 형식 400, 없는 인증 404, 다른 번호·미확인·오래된 인증·다른 목적 403, 운영자 아님 403")
    void registerRejected() throws Exception {
        String phone = accounts.newPhone();
        long verificationId = verifiedSignup(phone);

        register(staff, "{\"phone\":\"" + phone + "\",\"phoneVerificationId\":" + verificationId + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[*].field", hasItem("businessName")));
        register(staff, registerBody("덕구보일러", "02-123-4567", verificationId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("phone"));
        register(staff, registerBody("덕구보일러", phone, verificationId).replace("031-123-4567", "12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("contactPhone"));
        register(staff, registerBody("덕구보일러", phone, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));

        // 다른 번호의 인증 / 아직 확인 안 한 인증 / 31분 전 인증 / 로그인 인증
        String other = accounts.newPhone();
        long unconfirmed = sendSignup(staff, other);
        long old = seededVerification(phone, "OWNER_SIGNUP", "31 minutes");
        long login = seededVerification(phone, "OWNER_LOGIN", "1 minute");
        for (long wrong : new long[]{verifiedSignup(accounts.newPhone()), old, login}) {
            register(staff, registerBody("덕구보일러", phone, wrong))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        }
        register(staff, registerBody("덕구보일러", other, unconfirmed))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));

        // 사장님 토큰으로는 운영 경로를 못 씀, 토큰 없으면 401
        String ownerToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        register(ownerToken, registerBody("덕구보일러", phone, verificationId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post(OWNERS).contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("덕구보일러", phone, verificationId)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owner WHERE phone IN (?, ?)", Integer.class, phone,
                other)).isZero();
    }

    @Test
    @DisplayName("목록·상세 - 심사 상태·운영 상태·검색어로 거르고 최근 등록부터, 고객·예약 내용은 없음")
    void listAndDetail() throws Exception {
        // 검색어가 다른 테스트의 업체와 섞이지 않게 이 테스트만의 꼬리표
        String tag = "t" + System.nanoTime();
        String pendingPhone = accounts.newPhone();
        long pending = registered(pendingPhone, "가람보일러 " + tag);
        long approved = registered(accounts.newPhone(), "나래설비 " + tag);
        approve(staff, approved).andExpect(status().isOk());
        long restricted = registered(accounts.newPhone(), "다온보일러 " + tag);
        approve(staff, restricted).andExpect(status().isOk());
        changeStatus(staff, restricted, "RESTRICTED").andExpect(status().isOk());
        reservation(restricted);

        getAs(staff, OWNERS + "?q=" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(3)))
                .andExpect(jsonPath("$.result.content[0].ownerId").value(restricted))
                .andExpect(jsonPath("$.result.content[2].ownerId").value(pending))
                .andExpect(jsonPath("$.result.content[2].phone").value(pendingPhone))
                .andExpect(jsonPath("$.result.content[2].approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.result.content[2].reviewedAt").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].status").value("RESTRICTED"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.isLast").value(true));
        getAs(staff, OWNERS + "?q=" + tag + "&approvalStatus=APPROVED")
                .andExpect(jsonPath("$.result.content[*].ownerId", hasSize(2)))
                .andExpect(jsonPath("$.result.content[*].ownerId", not(hasItem((int) pending))));
        getAs(staff, OWNERS + "?q=" + tag + "&approvalStatus=APPROVED&status=RESTRICTED")
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].ownerId").value(restricted));
        // 상호 일부(대소문자 무시)·쪽 나누기
        getAs(staff, OWNERS + "?q=나래설비 " + tag.toUpperCase())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].ownerId").value(approved));
        getAs(staff, OWNERS + "?q=" + tag + "&page=1&size=2")
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].ownerId").value(pending))
                .andExpect(jsonPath("$.result.totalPages").value(2))
                .andExpect(jsonPath("$.result.isLast").value(true));
        // 숫자·하이픈만이면 로그인 번호로 찾음
        getAs(staff, OWNERS + "?q=" + pendingPhone.substring(0, 3) + "-" + pendingPhone.substring(3, 7) + "-"
                + pendingPhone.substring(7))
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].ownerId").value(pending));
        // 검색어의 %는 글자 그대로 - 전체가 걸리지 않음
        getAs(staff, OWNERS + "?q=%25")
                .andExpect(jsonPath("$.result.content", hasSize(0)));

        getAs(staff, OWNERS + "?approvalStatus=DONE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        getAs(staff, OWNERS + "?page=-1&size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[*].field", hasItem("page")))
                .andExpect(jsonPath("$.result[*].field", hasItem("size")));

        // 상세 - 계정·업체 상태만 (고객·예약 칸이 없음)
        String detail = getAs(staff, OWNERS + "/" + restricted)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value(restricted))
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.result.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.result.reviewedBy.adminId").value(staffId))
                .andExpect(jsonPath("$.result.reviewedBy.name").value("운영자"))
                .andExpect(jsonPath("$.result.reviewedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.result.intakeLinkActive").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("김고객").doesNotContain("reservation").doesNotContain("linkToken");
        getAs(staff, OWNERS + "/999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("심사 - 승인하면 그 번호로 로그인되고 자기 업체만, 거절은 사유 필수, 이미 심사했으면 409")
    void review() throws Exception {
        String phone = accounts.newPhone();
        long ownerId = registered(phone, "덕구보일러");

        approve(staff, ownerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"))
                // 승인은 운영 상태·자동 예약을 켜지 않음
                .andExpect(jsonPath("$.result.status").value("READY"))
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(false))
                .andExpect(jsonPath("$.result.reviewedBy.adminId").value(staffId))
                .andExpect(jsonPath("$.result.reviewedAt", notNullValue()))
                .andExpect(jsonPath("$.result.intakeLinkActive").value(true));
        assertThat(owner(ownerId)).containsEntry("approval_status", "APPROVED").containsEntry("status", "READY")
                .containsEntry("reviewed_by", staffId).containsEntry("rejection_reason", null);

        // 승인 뒤 그 번호로 로그인 → 자기 업체만
        String code = loginCode(phone);
        String ownerToken = JsonPath.read(code, "$.result.accessToken");
        getAs(ownerToken, OWNER_ME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value(ownerId))
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"));
        // 사장님 토큰으로 운영 경로는 못 씀
        getAs(ownerToken, OWNERS + "/" + ownerId).andExpect(status().isForbidden());

        // 이미 심사한 신청 - 다시 승인·거절 409 (지금 심사 상태와 함께)
        approve(superAdmin, ownerId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_ALREADY_REVIEWED"))
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"));
        reject(staff, ownerId, "{\"reason\":\"마음이 바뀜\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_ALREADY_REVIEWED"));
        assertThat(owner(ownerId)).containsEntry("approval_status", "APPROVED").containsEntry("reviewed_by", staffId);

        // 거절 - 사유 없음·빈 사유 400, 사유와 함께 REJECTED → 로그인 문자 대신 사유를 알려 줌
        String rejectedPhone = accounts.newPhone();
        long rejected = registered(rejectedPhone, "거절보일러");
        for (String body : new String[]{"{}", "{\"reason\":\"  \"}"}) {
            reject(staff, rejected, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                    .andExpect(jsonPath("$.result[0].field").value("reason"));
        }
        reject(staff, rejected, "{\"reason\":\" 사업자 확인 불가 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.approvalStatus").value("REJECTED"))
                .andExpect(jsonPath("$.result.rejectionReason").value("사업자 확인 불가"))
                .andExpect(jsonPath("$.result.reviewedBy.adminId").value(staffId))
                .andExpect(jsonPath("$.result.intakeLinkActive").value(false));
        send(null, "OWNER_LOGIN", rejectedPhone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_REJECTED"))
                .andExpect(jsonPath("$.result.rejectionReason").value("사업자 확인 불가"));
        approve(staff, rejected)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.result.approvalStatus").value("REJECTED"));
        approve(staff, 999_999_999L).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("운영 상태 - 승인된 업체만, 이용 제한이면 로그인이 끊기고 예약·고객은 그대로, 풀면 다시 로그인")
    void operationStatus() throws Exception {
        String phone = accounts.newPhone();
        long ownerId = registered(phone, "덕구보일러");

        // 승인 전에는 운영 상태를 바꾸지 못함
        changeStatus(staff, ownerId, "ACTIVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_APPROVED"));
        approve(staff, ownerId).andExpect(status().isOk());

        changeStatus(staff, ownerId, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                // 운영 상태를 바꿔도 자동 예약은 사장님만 켬
                .andExpect(jsonPath("$.result.autoBookingEnabled").value(false));
        long reservationId = reservation(ownerId);
        String ownerToken = JsonPath.read(loginCode(phone), "$.result.accessToken");
        getAs(ownerToken, OWNER_ME).andExpect(status().isOk());
        assertThat(liveOwnerTokens(ownerId)).isEqualTo(1);

        // 같은 상태로 다시 - 200, 토큰 그대로
        changeStatus(staff, ownerId, "ACTIVE").andExpect(status().isOk());
        assertThat(liveOwnerTokens(ownerId)).isEqualTo(1);

        changeStatus(staff, ownerId, "RESTRICTED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"));
        // 사장님 로그인 토큰이 모두 끊기고 새 로그인도 막힘, 예약·고객은 그대로
        assertThat(liveOwnerTokens(ownerId)).isZero();
        getAs(ownerToken, OWNER_ME).andExpect(status().isUnauthorized());
        waitedForResend(phone);
        send(null, "OWNER_LOGIN", phone)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_RESTRICTED"));
        assertThat(reservationState(reservationId)).isEqualTo("CONFIRMED/김고객");

        // 제한을 풀면 다시 로그인 (끊긴 토큰은 되살아나지 않음)
        changeStatus(staff, ownerId, "ACTIVE").andExpect(status().isOk());
        getAs(ownerToken, OWNER_ME).andExpect(status().isUnauthorized());
        waitedForResend(phone);
        String again = JsonPath.read(loginCode(phone), "$.result.accessToken");
        getAs(again, OWNER_ME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("ACTIVE"));

        // 탈퇴는 이 API로 만들 수 없고, 모르는 값·빈 값은 400
        changeStatus(staff, ownerId, "WITHDRAWN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("status"));
        changeStatus(staff, ownerId, "PAUSED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(as(staff, patch(OWNERS + "/" + ownerId + "/status")).content("{}"))
                .andExpect(status().isBadRequest());
        // 탈퇴한 업체는 운영 상태를 바꾸지 않음
        jdbc.update("UPDATE owner SET status = 'WITHDRAWN' WHERE id = ?", ownerId);
        changeStatus(staff, ownerId, "ACTIVE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_WITHDRAWN"));
        assertThat(owner(ownerId)).containsEntry("status", "WITHDRAWN");
        assertThat(reservationState(reservationId)).isEqualTo("CONFIRMED/김고객");
    }

    @Test
    @DisplayName("로그인 번호 변경과 옛 번호 로그인이 겹침 - 로그인은 번호 변경이 끝난 뒤에 처리돼 옛 번호로 토큰이 나가지 않음")
    void loginDuringPhoneChange() throws Exception {
        String oldPhone = accounts.newPhone();
        long ownerId = registered(oldPhone, "덕구보일러");
        approve(staff, ownerId).andExpect(status().isOk());
        changeStatus(staff, ownerId, "ACTIVE").andExpect(status().isOk());
        // 옛 번호로 로그인 인증번호까지 받아 둠
        String sent = send(null, "OWNER_LOGIN", oldPhone)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long loginVerificationId = ((Number) JsonPath.read(sent, "$.result.verificationId")).longValue();
        String code = smsSender.lastCode(oldPhone);
        String newPhone = accounts.newPhone();
        long verificationId = verifiedSignup(newPhone);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 번호 변경이 아직 끝나지 않은(커밋 전) 사이에 옛 번호의 로그인 확인이 들어옴
            Future<MvcResult> login = transactionTemplate.execute(tx -> {
                try {
                    changeLoginPhone(superAdmin, ownerId, newPhone, verificationId).andExpect(status().isOk());
                    Future<MvcResult> confirming = executor.submit(
                            () -> confirm(loginVerificationId, code).andReturn());
                    try {
                        confirming.get(1, TimeUnit.SECONDS);
                    } catch (TimeoutException waitingForLock) {
                        // 업체 행 잠금을 기다리는 중 - 번호 변경이 끝나야 진행
                    }
                    return confirming;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            MvcResult result = login.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).contains("OWNER_NOT_REGISTERED");
            assertThat(liveOwnerTokens(ownerId)).isZero();
            assertThat(owner(ownerId)).containsEntry("phone", newPhone);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("로그인 번호 변경 - SUPER 운영자만, 새 번호 인증 필요, 번호만 바뀌고 기존 로그인은 끊기며 예약·고객은 그대로")
    void changeLoginPhone() throws Exception {
        String oldPhone = accounts.newPhone();
        long ownerId = registered(oldPhone, "덕구보일러");
        approve(staff, ownerId).andExpect(status().isOk());
        changeStatus(staff, ownerId, "ACTIVE").andExpect(status().isOk());
        long reservationId = reservation(ownerId);
        String ownerToken = JsonPath.read(loginCode(oldPhone), "$.result.accessToken");
        String newPhone = accounts.newPhone();
        long verificationId = verifiedSignup(newPhone);

        // STAFF는 못 함
        changeLoginPhone(staff, ownerId, newPhone, verificationId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        // 번호 형식·없는 인증·다른 번호의 인증
        changeLoginPhone(superAdmin, ownerId, "031-123-4567", verificationId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result[0].field").value("newPhone"));
        changeLoginPhone(superAdmin, ownerId, newPhone, 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
        changeLoginPhone(superAdmin, ownerId, newPhone, verifiedSignup(accounts.newPhone()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_PURPOSE_MISMATCH"));
        // 인증한 뒤 그 번호가 다른 업체의 로그인 번호가 됨 - 409
        String taken = accounts.newPhone();
        long takenVerification = verifiedSignup(taken);
        accounts.approvedOwner(taken);
        changeLoginPhone(superAdmin, ownerId, taken, takenVerification)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_PHONE_DUPLICATED"));
        assertThat(owner(ownerId)).containsEntry("phone", oldPhone);
        getAs(ownerToken, OWNER_ME).andExpect(status().isOk());

        changeLoginPhone(superAdmin, ownerId, newPhone, verificationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.phone").value(newPhone))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.approvalStatus").value("APPROVED"));
        // 번호만 바뀜 - 업체·예약·고객 그대로, 기존 로그인은 끊김
        assertThat(owner(ownerId)).containsEntry("phone", newPhone).containsEntry("business_name", "덕구보일러")
                .containsEntry("status", "ACTIVE");
        assertThat(reservationState(reservationId)).isEqualTo("CONFIRMED/김고객");
        assertThat(liveOwnerTokens(ownerId)).isZero();
        getAs(ownerToken, OWNER_ME).andExpect(status().isUnauthorized());
        // 옛 번호는 등록 안 된 번호, 새 번호로 로그인
        waitedForResend(oldPhone);
        send(null, "OWNER_LOGIN", oldPhone)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_REGISTERED"));
        String fresh = JsonPath.read(loginCode(newPhone), "$.result.accessToken");
        getAs(fresh, OWNER_ME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ownerId").value(ownerId))
                .andExpect(jsonPath("$.result.phone").value(newPhone));

        // 탈퇴한 업체는 번호를 바꾸지 않음
        jdbc.update("UPDATE owner SET status = 'WITHDRAWN' WHERE id = ?", ownerId);
        String another = accounts.newPhone();
        changeLoginPhone(superAdmin, ownerId, another, verifiedSignup(another))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_WITHDRAWN"));
    }

    @Test
    @DisplayName("접수 링크 재발급 - 승인된 업체만, 기존 링크는 폐기되고 예약은 그대로 (O-04·O-06)")
    void reissueIntakeLink() throws Exception {
        long ownerId = registered(accounts.newPhone(), "덕구보일러");
        mockMvc.perform(as(staff, post(OWNERS + "/" + ownerId + "/intake-link")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_APPROVED"));
        approve(staff, ownerId).andExpect(status().isOk());
        changeStatus(staff, ownerId, "ACTIVE").andExpect(status().isOk());
        long reservationId = reservation(ownerId);
        String before = (String) owner(ownerId).get("link_token");
        mockMvc.perform(get("/api/v1/public/intake/" + before)).andExpect(status().isOk());

        mockMvc.perform(as(staff, post(OWNERS + "/" + ownerId + "/intake-link")))
                .andExpect(status().isOk());
        String after = (String) owner(ownerId).get("link_token");
        assertThat(after).isNotEqualTo(before);
        mockMvc.perform(get("/api/v1/public/intake/" + before)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/intake/" + after)).andExpect(status().isOk());
        assertThat(reservationState(reservationId)).isEqualTo("CONFIRMED/김고객");
    }

    // ---------- 도움 함수 ----------

    // 운영자가 그 번호로 OWNER_SIGNUP 인증을 보내고 확인까지 마침 → 인증 ID
    private long verifiedSignup(String phone) throws Exception {
        long verificationId = sendSignup(staff, phone);
        confirm(verificationId, smsSender.lastCode(phone)).andExpect(status().isOk());
        return verificationId;
    }

    private long sendSignup(String token, String phone) throws Exception {
        String body = send(token, "OWNER_SIGNUP", phone)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.verificationId")).longValue();
    }

    // 인증번호 발송 - 재발송 간격(60초)·IP 한도에 걸리지 않게 테스트마다 다른 IP
    private ResultActions send(String token, String purpose, String phone) throws Exception {
        MockHttpServletRequestBuilder request = post(VERIFICATIONS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"" + purpose + "\",\"phone\":\"" + phone + "\"}")
                .with(req -> {
                    req.setRemoteAddr(ip);
                    return req;
                });
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    // 그 번호의 발송 기록을 과거로 옮김 - 재발송 간격(60초) 검사가 업체 상태 검사보다 먼저라서
    private void waitedForResend(String phone) {
        jdbc.update("UPDATE phone_verification SET created_at = created_at - interval '2 minutes' WHERE phone = ?",
                phone);
    }

    // 그 번호로 사장님 로그인 (문자 인증 → 확인) → 확인 응답 본문
    private String loginCode(String phone) throws Exception {
        String sent = send(null, "OWNER_LOGIN", phone)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long verificationId = ((Number) JsonPath.read(sent, "$.result.verificationId")).longValue();
        return confirm(verificationId, smsSender.lastCode(phone))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // 이미 확인까지 마친 인증 기록을 바로 넣음 - verifiedAgo 전에 확인한 것으로
    private long seededVerification(String phone, String purpose, String verifiedAgo) {
        return jdbc.queryForObject("INSERT INTO phone_verification (phone, purpose, code_hash, send_status, "
                + "expires_at, verified_at, created_at) VALUES (?, ?, 'seeded', 'SENT', "
                + "CURRENT_TIMESTAMP + interval '5 minutes', CURRENT_TIMESTAMP - ?::interval, "
                + "CURRENT_TIMESTAMP - ?::interval - interval '2 minutes') RETURNING id", Long.class, phone, purpose,
                verifiedAgo, verifiedAgo);
    }

    private ResultActions confirm(long verificationId, String code) throws Exception {
        return mockMvc.perform(post(VERIFICATIONS + "/" + verificationId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    // 운영팀 등록 → 업체 ID (끝나면 지우게 등록해 둠)
    private long registered(String phone, String businessName) throws Exception {
        String body = register(staff, registerBody(businessName, phone, verifiedSignup(phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long ownerId = ((Number) JsonPath.read(body, "$.result.ownerId")).longValue();
        accounts.trackOwner(ownerId);
        return ownerId;
    }

    private static String registerBody(String businessName, String phone, long verificationId) {
        return "{\"businessName\":\"" + businessName + "\",\"ownerName\":\"김사장\",\"phone\":\"" + phone
                + "\",\"phoneVerificationId\":" + verificationId + ",\"contactPhone\":\"031-123-4567\","
                + "\"address\":\"경기 고양시 덕양구 화정로 56\",\"serviceDescription\":\"보일러 점검·수리·교체\"}";
    }

    private ResultActions register(String token, String body) throws Exception {
        return mockMvc.perform(as(token, post(OWNERS)).content(body));
    }

    private ResultActions approve(String token, long ownerId) throws Exception {
        return mockMvc.perform(as(token, post(OWNERS + "/" + ownerId + "/approve")));
    }

    private ResultActions reject(String token, long ownerId, String body) throws Exception {
        return mockMvc.perform(as(token, post(OWNERS + "/" + ownerId + "/reject")).content(body));
    }

    private ResultActions changeStatus(String token, long ownerId, String status) throws Exception {
        return mockMvc.perform(as(token, patch(OWNERS + "/" + ownerId + "/status"))
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions changeLoginPhone(String token, long ownerId, String newPhone, long verificationId)
            throws Exception {
        return mockMvc.perform(as(token, put(OWNERS + "/" + ownerId + "/login-phone"))
                .content("{\"newPhone\":\"" + newPhone + "\",\"phoneVerificationId\":" + verificationId + "}"));
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(as(token, get(path)));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> owner(long ownerId) {
        return jdbc.queryForMap("SELECT business_name, owner_name, phone, contact_phone, address, lat, "
                + "service_description, approval_status, status, rejection_reason, reviewed_by, reviewed_at, "
                + "auto_booking_enabled, link_token FROM owner WHERE id = ?", ownerId);
    }

    // 그 업체의 고객 1명 + 확정 예약 1건 → 예약 ID
    private long reservation(long ownerId) {
        long customerId = jdbc.queryForObject("INSERT INTO customer (owner_id, name, phone) VALUES (?, '김고객', ?) "
                + "RETURNING id", Long.class, ownerId, accounts.newPhone());
        return jdbc.queryForObject("INSERT INTO reservation (owner_id, customer_id, start_at, end_at, address, "
                + "status) VALUES (?, ?, CURRENT_TIMESTAMP + interval '3 days', CURRENT_TIMESTAMP + interval '3 days' "
                + "+ interval '1 hour', '경기 고양시 덕양구 화정로 1', 'CONFIRMED') RETURNING id", Long.class, ownerId,
                customerId);
    }

    private String reservationState(long reservationId) {
        return jdbc.queryForObject("SELECT r.status || '/' || c.name FROM reservation r JOIN customer c "
                + "ON c.id = r.customer_id WHERE r.id = ?", String.class, reservationId);
    }

    private int liveOwnerTokens(long ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_token WHERE owner_id = ? AND token_type = 'OWNER' "
                + "AND revoked_at IS NULL", Integer.class, ownerId);
    }
}
