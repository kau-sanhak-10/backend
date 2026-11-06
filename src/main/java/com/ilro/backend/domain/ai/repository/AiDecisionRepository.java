package com.ilro.backend.domain.ai.repository;

import com.ilro.backend.domain.ai.entity.AiAction;
import com.ilro.backend.domain.ai.entity.AiDecision;
import com.ilro.backend.domain.ai.entity.AiDecisionStatus;
import com.ilro.backend.domain.ai.entity.AiStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiDecisionRepository extends JpaRepository<AiDecision, Long> {

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
