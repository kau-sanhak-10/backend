package com.ilro.backend.infra.ai;

import com.ilro.backend.domain.inquiry.entity.MessageSender;
import com.ilro.backend.infra.ai.AiCallResult.Outcome;
import com.ilro.backend.infra.ai.AnalyzeRequest.MessageItem;
import com.ilro.backend.infra.ai.AnalyzeResult.Evidence;
import com.ilro.backend.infra.ai.AnalyzeResult.Fact;
import com.ilro.backend.infra.ai.AnalyzeResult.NextAction;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// AI 서버 없이 명세 6.3 모양의 답을 바로 돌려줌 - 로컬·프론트 개발, AI 서버 연결 전 개발 서버용
// (10/26 "명세 기반 가짜 응답으로 병렬 개발한 뒤 11/3~11/9 연결")
// 증상이 아직 없으면 마지막 고객 메시지를 증상으로 받아 적고, 증상이 있으면 방문 시간 고르기로 넘김
// 실패 흉내: 마지막 고객 메시지에 #fake-timeout · #fake-invalid · #fake-error · #fake-escalate · #fake-safety
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake", matchIfMissing = true)
public class FakeAiAnalyzeClient implements AiAnalyzeClient {

    private final JsonMapper jsonMapper;

    private record Envelope(boolean success, String code, String message, AnalyzeResult result) {
    }

    @Override
    public AiCallResult analyze(AnalyzeRequest request, Duration timeout) {
        Optional<MessageItem> last = request.messages().stream()
                .filter(message -> message.sender() == MessageSender.CUSTOMER)
                .reduce((first, second) -> second);
        String text = last.map(MessageItem::content).orElse("").strip();
        if (text.contains("#fake-timeout")) {
            return AiCallResult.failure(Outcome.TIMEOUT, true, null, "AI_MODEL_TIMEOUT", 0);
        }
        if (text.contains("#fake-invalid")) {
            return AiCallResult.failure(Outcome.INVALID_OUTPUT, true, null, "AI_OUTPUT_INVALID", 0);
        }
        if (text.contains("#fake-error")) {
            return AiCallResult.failure(Outcome.FAILED, false, null, "AI_MODEL_ERROR", 0);
        }

        List<Fact> facts = new ArrayList<>();
        boolean hasSymptom = request.fields().stream()
                .anyMatch(field -> field.fieldKey().equals("symptom") && field.value() != null);
        if (!hasSymptom && last.isPresent() && !text.isEmpty()) {
            facts.add(new Fact("symptom", text.length() > 255 ? text.substring(0, 255) : text, "CUSTOMER",
                    "CONFIRMED", null, new Evidence(last.get().messageId(), null)));
            hasSymptom = true;
        }
        boolean safety = text.contains("#fake-safety");
        NextAction next;
        String reply;
        if (safety) {
            next = new NextAction("ESCALATE_TO_OWNER", "SAFETY", List.of(), "안전 확인 필요");
            reply = "안전을 위해 보일러 전원을 끄고 사장님 연락을 기다려 주세요.";
        } else if (text.contains("#fake-escalate")) {
            next = new NextAction("ESCALATE_TO_OWNER", "LOW_CONFIDENCE", List.of(), "사장님 확인 요청");
            reply = "사장님께 전달했어요. 확인 후 연락드릴게요.";
        } else if (!hasSymptom) {
            next = new NextAction("ASK_MORE_INFO", "MISSING_FIELD", List.of("symptom"), "증상 없음");
            reply = "어떤 증상인지 조금 더 알려 주세요.";
        } else if (request.business().autoBookingEnabled() && !request.business().activeWorkCategories().isEmpty()) {
            next = new NextAction("CHECK_SCHEDULE", "DONE", List.of(), "증상 확인");
            reply = "방문 가능한 시간을 골라 주세요.";
        } else {
            next = new NextAction("COMPLETE", "DONE", List.of(), "증상 확인");
            reply = "접수 내용을 사장님께 전달할게요.";
        }

        AnalyzeResult result = new AnalyzeResult(request.requestId(), request.inquiryId(), request.inputVersion(),
                "fake", "fake", "RULE", "fake", new AnalyzeResult.Usage(0, 0, 0), facts, List.of(), List.of(),
                new AnalyzeResult.WorkCategoryDecision(null, "UNCONFIRMED"),
                new AnalyzeResult.Safety(safety, safety ? List.of("FAKE") : List.of()), "ANSWER", next,
                new AnalyzeResult.Reply("[가짜 응답] " + reply), null);
        return AiCallResult.success(result,
                jsonMapper.writeValueAsString(new Envelope(true, "OK", "요청 성공", result)), 0);
    }
}
