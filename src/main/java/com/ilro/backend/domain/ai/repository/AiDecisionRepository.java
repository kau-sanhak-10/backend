package com.ilro.backend.domain.ai.repository;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiDecisionRepository extends JpaRepository<AiDecision, Long> {

    // 이미 저장하는 성공 응답에서 사진 완료 ID만 읽는다. 실패·STALE 결과는 분석 이력이 아니다.
    @Query(value = """
            SELECT DISTINCT CAST(photo.id AS text)
            FROM ai_decision d
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(d.output_raw #> '{result,analyzedPhotoIds}') = 'array'
                    THEN d.output_raw #> '{result,analyzedPhotoIds}' ELSE CAST('[]' AS jsonb) END
            ) AS photo(id)
            WHERE d.inquiry_id = :inquiryId AND d.stage = 'DECIDE' AND d.status = 'SUCCESS'
                AND jsonb_typeof(photo.id) = 'number'
            """, nativeQuery = true)
    List<String> findAnalyzedPhotoIds(Long inquiryId);

    // 고객 화면 다음 안내 - 적용된 마지막 판단 (실패·오래된 결과는 빼고)
    Optional<AiDecision> findFirstByInquiryIdAndStageAndStatusOrderByIdDesc(Long inquiryId, AiStage stage,
                                                                           AiDecisionStatus status);

    // 같은 요청의 결과는 한 번만 적용 (명세 6.4)
    boolean existsByInquiryIdAndRequestIdAndStatus(Long inquiryId, String requestId, AiDecisionStatus status);

    // 이 입력 버전으로 실패한 호출 - 서버가 다시 켜져도 같은 입력의 호출 횟수를 이어서 셈
    List<AiDecision> findByInquiryIdAndInputVersionAndStatusInOrderByIdAsc(Long inquiryId, Long inputVersion,
                                                                          Collection<AiDecisionStatus> statuses);

    // 항목별 보완 질문 수 세기 (명세 6.2 askCounts)
    List<AiDecision> findByInquiryIdAndStageAndStatusAndActionIn(Long inquiryId, AiStage stage,
                                                                AiDecisionStatus status,
                                                                Collection<AiAction> actions);
}
