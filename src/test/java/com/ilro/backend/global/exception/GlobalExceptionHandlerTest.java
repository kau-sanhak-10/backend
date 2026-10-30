package com.ilro.backend.global.exception;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.reservation.entity.Reservation;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 예외 → 오류 응답 (명세 3.10의 Spring·JPA 예외 표), DB 없이 확인
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("BusinessException의 result가 응답에 담긴다")
    void businessExceptionWithResult() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.result.retryAfterSeconds").value(30));
    }

    @Test
    @DisplayName("경로·쿼리 값 검증 실패는 400 INVALID_INPUT + 보낸 이름 그대로의 칸별 이유")
    void methodValidation() throws Exception {
        mockMvc.perform(get("/test/page").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("page"))
                .andExpect(jsonPath("$.result[0].reason").isNotEmpty());
    }

    @Test
    @DisplayName("형식이 틀린 경로 값은 400 INVALID_INPUT + 파라미터 이름")
    void typeMismatch() throws Exception {
        mockMvc.perform(get("/test/inquiries/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("inquiryId"));
    }

    @Test
    @DisplayName("필수 쿼리·헤더가 없으면 400 INVALID_INPUT + 그 이름")
    void missingParameterAndHeader() throws Exception {
        mockMvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("phone"));

        mockMvc.perform(post("/test/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("Idempotency-Key"));
    }

    @Test
    @DisplayName("JSON이 아닌 본문은 415 UNSUPPORTED_MEDIA_TYPE")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/test/reservations").header("Idempotency-Key", "k")
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("@Version 충돌은 예약·문의면 각자의 코드, 그 밖은 DATA_CONFLICT (모두 409, result 없음)")
    void optimisticLock() throws Exception {
        mockMvc.perform(get("/test/optimistic").param("entity", "reservation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        mockMvc.perform(get("/test/optimistic").param("entity", "inquiry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        mockMvc.perform(get("/test/optimistic").param("entity", "owner"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    @DisplayName("DB 제약 위반은 대응표대로 바뀌고 result도 담긴다")
    void dataIntegrityViolation() throws Exception {
        mockMvc.perform(get("/test/constraint").param("name", "ex_reservation_confirmed_overlap"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.result.reason").value("OVERLAP"));
        mockMvc.perform(get("/test/constraint").param("name", "fk_reservation_inquiry_same_owner"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INQUIRY_NOT_FOUND"));
        mockMvc.perform(get("/test/constraint").param("name", "uq_owner_business_hour_owner_day"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.result[0].field").value("businessHours"));
        mockMvc.perform(get("/test/constraint").param("name", "uq_something_new"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    @DisplayName("잠금 대기 초과·교착은 503 SERVICE_UNAVAILABLE + Retry-After: 1")
    void pessimisticLock() throws Exception {
        mockMvc.perform(get("/test/lock"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfterSeconds", 30));
        }

        @GetMapping("/test/page")
        void page(@RequestParam("page") @Min(1) int pageNumber) {
        }

        @GetMapping("/test/inquiries/{inquiryId}")
        void inquiry(@PathVariable Long inquiryId) {
        }

        @GetMapping("/test/required")
        void required(@RequestParam String phone) {
        }

        @PostMapping("/test/reservations")
        void reservation(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Map<String, Object> body) {
        }

        @GetMapping("/test/optimistic")
        void optimistic(@RequestParam String entity) {
            Class<?> entityClass = switch (entity) {
                case "reservation" -> Reservation.class;
                case "inquiry" -> Inquiry.class;
                default -> Owner.class;
            };
            throw new ObjectOptimisticLockingFailureException(entityClass, 1L);
        }

        @GetMapping("/test/constraint")
        void constraint(@RequestParam String name) {
            ServerErrorMessage serverError = new ServerErrorMessage("SERROR\0C23505\0Mviolation\0n" + name + "\0");
            throw new DataIntegrityViolationException("violation", new PSQLException(serverError));
        }

        @GetMapping("/test/lock")
        void lock() {
            throw new CannotAcquireLockException("lock timeout");
        }
    }
}
