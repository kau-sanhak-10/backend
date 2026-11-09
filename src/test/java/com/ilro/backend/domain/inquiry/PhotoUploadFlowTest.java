package com.ilro.backend.domain.inquiry;

import com.ilro.backend.infra.storage.LocalPhotoStorage;
import com.ilro.backend.support.TestAccounts;
import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 고객 사진 업로드·보기 + 사장님 사진 보기 (BE-06, C-05·B-13) - 실제 토큰 + 가짜 저장소(LocalPhotoStorage)
@SpringBootTest
class PhotoUploadFlowTest {

    private static final String ALL_CONSENTS = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"},
                                                  {"type":"PHOTO","termsVersion":"2026-10-v1"}]}""";
    private static final String PRIVACY_ONLY = """
            {"consents":[{"type":"PRIVACY_REQUIRED","termsVersion":"2026-10-v1"}]}""";
    private static final long SIZE = 3_000_000L;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LocalPhotoStorage storage;

    private MockMvc mockMvc;
    private TestAccounts accounts;
    private String link;
    private String ownerToken;

    private record Session(long inquiryId, String token) {

        String path(String suffix) {
            return "/api/v1/customer/inquiries/" + inquiryId + "/photos" + suffix;
        }

        String bearer() {
            return "Bearer " + token;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        accounts = new TestAccounts(jdbc);
        long ownerId = accounts.approvedOwner(accounts.newPhone());
        link = accounts.linkToken(ownerId);
        ownerToken = accounts.ownerToken(ownerId);
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    @Test
    @DisplayName("업로드 시작 → 저장소에 올림 → 완료 확인 → 메시지에 첨부까지")
    void uploadFlow() throws Exception {
        Session s = start(ALL_CONSENTS);
        String body = upload(s, "image/jpeg", SIZE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.method").value("PUT"))
                .andExpect(jsonPath("$.result.headers['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.result.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.result.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long photoId = ((Number) JsonPath.read(body, "$.result.photoId")).longValue();
        // 경로에 원래 파일 이름 없이 문의 번호 + 무작위 이름
        assertThat(key(photoId)).matches("inquiries/" + s.inquiryId() + "/[0-9a-f-]{36}\\.jpg");
        assertThat(photoStatus(photoId)).isEqualTo("UPLOADING");

        storage.putForTest(key(photoId), SIZE, "image/jpeg");
        complete(s, photoId).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.uploadStatus").value("DONE"));
        // 같은 요청을 다시 보내도 같은 결과
        complete(s, photoId).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.uploadStatus").value("DONE"));

        mockMvc.perform(post("/api/v1/customer/inquiries/" + s.inquiryId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMsgId\":\"" + UUID.randomUUID() + "\",\"photoIds\":[" + photoId + "]}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT message_id FROM photo WHERE id = ?", Long.class, photoId)).isNotNull();
    }

    @Test
    @DisplayName("완료 확인 - 저장소에 아직 없으면 409에 상태 그대로, 크기·형식이 다르면 실패")
    void completeChecksStoredFile() throws Exception {
        Session s = start(ALL_CONSENTS);
        long waiting = startUpload(s, "image/png", SIZE);
        complete(s, waiting).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_UPLOADED"));
        assertThat(photoStatus(waiting)).isEqualTo("UPLOADING");

        long wrongSize = startUpload(s, "image/jpeg", SIZE);
        storage.putForTest(key(wrongSize), SIZE + 1, "image/jpeg");
        complete(s, wrongSize).andExpect(jsonPath("$.result.uploadStatus").value("FAILED"));

        long wrongType = startUpload(s, "image/jpeg", SIZE);
        storage.putForTest(key(wrongType), SIZE, "image/png");
        complete(s, wrongType).andExpect(jsonPath("$.result.uploadStatus").value("FAILED"));
    }

    @Test
    @DisplayName("업로드 시작 검사 - JPG·PNG만, 장당 10MB, 사진 동의 필요")
    void startValidation() throws Exception {
        Session s = start(ALL_CONSENTS);
        upload(s, "image/gif", SIZE).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO_TYPE_NOT_ALLOWED"));
        upload(s, "image/jpeg", 10_000_001L).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO_TOO_LARGE"));
        // 대소문자 상관없이, 딱 10MB는 허용
        upload(s, "IMAGE/JPEG", 10_000_000L).andExpect(status().isCreated());

        Session noPhotoConsent = start(PRIVACY_ONLY);
        upload(noPhotoConsent, "image/jpeg", SIZE).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo WHERE inquiry_id = ?", Long.class,
                noPhotoConsent.inquiryId())).isZero();
    }

    @Test
    @DisplayName("문의당 10장 - 업로드 중·완료만 세고, 실패한 사진은 빼고 셈")
    void photoLimit() throws Exception {
        Session s = start(ALL_CONSENTS);
        long first = startUpload(s, "image/jpeg", SIZE);
        for (int i = 0; i < 9; i++) {
            startUpload(s, "image/jpeg", SIZE);
        }
        upload(s, "image/jpeg", SIZE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT_EXCEEDED"));

        // 한 장이 실패하면 그만큼 다시 올릴 수 있음
        storage.putForTest(key(first), SIZE + 1, "image/jpeg");
        complete(s, first).andExpect(jsonPath("$.result.uploadStatus").value("FAILED"));
        upload(s, "image/jpeg", SIZE).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("버려진 업로드 - 업로드 주소가 만료되면 10장에서 빠지고, 완료 확인은 FAILED")
    void abandonedUpload() throws Exception {
        Session s = start(ALL_CONSENTS);
        long abandoned = startUpload(s, "image/jpeg", SIZE);
        for (int i = 0; i < 9; i++) {
            startUpload(s, "image/jpeg", SIZE);
        }
        upload(s, "image/jpeg", SIZE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT_EXCEEDED"));

        // 고객이 올리다 그만둠 - 업로드 주소 유효시간(5분)이 지난 것으로 만듦
        expireUpload(abandoned);
        // 화면이 아무것도 안 해도 자리가 풀림
        upload(s, "image/jpeg", SIZE).andExpect(status().isCreated());
        // 나중에 완료 확인을 부르면 기다리라는 409 대신 FAILED
        complete(s, abandoned).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.uploadStatus").value("FAILED"));
    }

    @Test
    @DisplayName("만료 후 늦게 완료한 사진 - 그 사이 자리가 찼으면 FAILED, 자리가 있으면 DONE")
    void lateCompleteAfterExpiry() throws Exception {
        Session s = start(ALL_CONSENTS);
        for (int i = 0; i < 8; i++) {
            uploaded(s);
        }
        // 파일은 올렸는데 완료 확인을 안 부르고 5분이 지남
        long late = startUpload(s, "image/jpeg", SIZE);
        storage.putForTest(key(late), SIZE, "image/jpeg");
        expireUpload(late);

        // 자리가 남아 있으면(DONE 8장) 늦게 와도 받아 줌
        complete(s, late).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.uploadStatus").value("DONE"));

        // 다시: DONE 9장 상태에서 만료된 사진 하나
        long abandoned = startUpload(s, "image/jpeg", SIZE);
        storage.putForTest(key(abandoned), SIZE, "image/jpeg");
        expireUpload(abandoned);
        // 풀린 자리를 새 사진이 채움 → DONE 10장
        uploaded(s);

        // 늦게 온 완료 확인은 11장째가 되므로 거절
        complete(s, abandoned).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.uploadStatus").value("FAILED"));
    }

    @Test
    @DisplayName("다른 문의 사진은 완료·보기 모두 404 - 있다는 사실도 숨김, 토큰 없으면 401")
    void otherInquiryPhoto() throws Exception {
        Session mine = start(ALL_CONSENTS);
        Session other = start(ALL_CONSENTS);
        long otherPhoto = uploaded(other);

        // 내 문의 경로로 남의 사진 번호
        complete(mine, otherPhoto).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));
        view(mine, otherPhoto).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));
        // 남의 문의 경로에 내 토큰
        mockMvc.perform(get(other.path("")).param("ids", String.valueOf(otherPhoto))
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        // 토큰 없이
        mockMvc.perform(post(mine.path("")).contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody("image/jpeg", SIZE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보기 주소 - 업로드를 마친 이 문의 사진만, 하나라도 아니면 전체 거절")
    void viewUrls() throws Exception {
        Session s = start(ALL_CONSENTS);
        long done1 = uploaded(s);
        long done2 = uploaded(s);
        long waiting = startUpload(s, "image/jpeg", SIZE);

        view(s, done1, done2).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.photos[*].photoId",
                        containsInAnyOrder(Math.toIntExact(done1), Math.toIntExact(done2))))
                .andExpect(jsonPath("$.result.photos[0].url", containsString("inquiries/" + s.inquiryId() + "/")))
                .andExpect(jsonPath("$.result.photos[0].expiresAt").isNotEmpty());
        view(s, done1, waiting).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));
    }

    @Test
    @DisplayName("사장님 사진 보기 - 자기 업체 문의의 업로드를 마친 사진만, 고객 토큰·다른 업체는 막음")
    void ownerViewUrl() throws Exception {
        Session s = start(ALL_CONSENTS);
        long done = uploaded(s);
        long waiting = startUpload(s, "image/jpeg", SIZE);
        Session otherInquiry = start(ALL_CONSENTS);
        long otherPhoto = uploaded(otherInquiry);

        ownerView(ownerToken, s, done).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.photoId").value(done))
                .andExpect(jsonPath("$.result.url", containsString("inquiries/" + s.inquiryId() + "/")))
                .andExpect(jsonPath("$.result.expiresAt").isNotEmpty());
        // 아직 올리는 중인 사진, 같은 업체의 다른 문의 사진 - 있다는 사실도 숨김
        ownerView(ownerToken, s, waiting).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));
        ownerView(ownerToken, s, otherPhoto).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));

        // 다른 업체 사장님
        String otherOwnerToken = accounts.ownerToken(accounts.approvedOwner(accounts.newPhone()));
        ownerView(otherOwnerToken, s, done).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        // 고객 문의 토큰으로 사장님 API, 토큰 없이
        ownerView(s.token(), s, done).andExpect(status().isForbidden());
        mockMvc.perform(get(ownerPhotoPath(s, done))).andExpect(status().isUnauthorized());
    }

    private Session start(String consents) throws Exception {
        String body = mockMvc.perform(post("/api/v1/public/intake/" + link + "/inquiries")
                        .contentType(MediaType.APPLICATION_JSON).content(consents))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Session(((Number) JsonPath.read(body, "$.result.inquiryId")).longValue(),
                JsonPath.read(body, "$.result.inquiryToken"));
    }

    private ResultActions upload(Session s, String contentType, long sizeBytes) throws Exception {
        return mockMvc.perform(post(s.path("")).header(HttpHeaders.AUTHORIZATION, s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(uploadBody(contentType, sizeBytes)));
    }

    private long startUpload(Session s, String contentType, long sizeBytes) throws Exception {
        String body = upload(s, contentType, sizeBytes).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.result.photoId")).longValue();
    }

    // 업로드를 마친(DONE) 사진 하나
    private long uploaded(Session s) throws Exception {
        long photoId = startUpload(s, "image/jpeg", SIZE);
        storage.putForTest(key(photoId), SIZE, "image/jpeg");
        complete(s, photoId).andExpect(jsonPath("$.result.uploadStatus").value("DONE"));
        return photoId;
    }

    private ResultActions complete(Session s, long photoId) throws Exception {
        return mockMvc.perform(post(s.path("/" + photoId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, s.bearer()));
    }

    private ResultActions view(Session s, long... photoIds) throws Exception {
        String ids = Arrays.stream(photoIds).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return mockMvc.perform(get(s.path("")).param("ids", ids).header(HttpHeaders.AUTHORIZATION, s.bearer()));
    }

    private String key(long photoId) {
        return jdbc.queryForObject("SELECT s3_key_original FROM photo WHERE id = ?", String.class, photoId);
    }

    private String photoStatus(long photoId) {
        return jdbc.queryForObject("SELECT upload_status FROM photo WHERE id = ?", String.class, photoId);
    }

    // 업로드를 시작한 지 10분 지난 것으로 - 업로드 주소(5분)가 만료된 상태
    private void expireUpload(long photoId) {
        jdbc.update("UPDATE photo SET created_at = created_at - interval '10 minutes' WHERE id = ?", photoId);
    }

    private ResultActions ownerView(String token, Session s, long photoId) throws Exception {
        return mockMvc.perform(get(ownerPhotoPath(s, photoId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String ownerPhotoPath(Session s, long photoId) {
        return "/api/v1/owner/inquiries/" + s.inquiryId() + "/photos/" + photoId + "/url";
    }


    private static String uploadBody(String contentType, long sizeBytes) {
        return "{\"contentType\":\"" + contentType + "\",\"sizeBytes\":" + sizeBytes + "}";
    }
}