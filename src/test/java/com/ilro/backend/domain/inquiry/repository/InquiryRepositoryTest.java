package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.owner.entity.Owner;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// AI 입력 순번(ai_input_seq)은 version과 따로 오르는지 확인 (명세 1.7)
@SpringBootTest
@Transactional
class InquiryRepositoryTest {

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private Inquiry inquiry;

    @BeforeEach
    void setUp() {
        Owner owner = Owner.builder()
                .businessName("일로보일러").phone(unique("o")).linkToken(unique("link")).build();
        em.persist(owner);
        inquiry = inquiryRepository.saveAndFlush(Inquiry.builder().owner(owner).build());
    }

    @Test
    @DisplayName("AI 입력 순번을 올려도 version은 그대로다")
    void incrementDoesNotTouchVersion() {
        assertThat(inquiryRepository.incrementAiInputSeq(inquiry.getId())).isEqualTo(1);
        assertThat(inquiryRepository.incrementAiInputSeq(inquiry.getId())).isEqualTo(1);

        assertThat(inquiryRepository.findAiInputSeq(inquiry.getId())).contains(2L);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry.getId()))
                .isZero();
    }

    @Test
    @DisplayName("순번을 올린 뒤 Entity를 수정·저장해도 순번을 덮어쓰지 않는다")
    void entityUpdateDoesNotOverwriteSeq() {
        inquiryRepository.incrementAiInputSeq(inquiry.getId());

        // 영속성 컨텍스트의 Entity는 순번 0을 들고 있음 → 다른 칸을 바꿔 전체 칸 UPDATE를 일으킴
        ReflectionTestUtils.setField(inquiry, "symptom", "온수가 안 나와요");
        em.flush();

        assertThat(jdbc.queryForObject("SELECT ai_input_seq FROM inquiry WHERE id = ?", Long.class, inquiry.getId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("없는 문의면 아무 행도 바뀌지 않는다")
    void incrementMissingInquiry() {
        assertThat(inquiryRepository.incrementAiInputSeq(-1L)).isZero();
        assertThat(inquiryRepository.findAiInputSeq(-1L)).isEmpty();
    }

    // UNIQUE 칸이 로컬 DB에 남아 있는 데이터와 겹치지 않게 임의 값 사용
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
