package com.ilro.backend.domain.boiler.service;

import com.ilro.backend.domain.boiler.dto.OfficialErrorCodeResponse;
import com.ilro.backend.domain.boiler.dto.OfficialErrorCodeResponse.Reason;
import com.ilro.backend.domain.boiler.entity.OfficialManualModel;
import com.ilro.backend.domain.boiler.repository.OfficialManualErrorCodeRepository;
import com.ilro.backend.domain.boiler.repository.OfficialManualModelRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.regex.Pattern;

// 오류코드 공식 설명 찾기 (AI-14) - 사장님 문의 상세에서 부름
// 확인된 제조사·모델·오류코드가 셋 다 공식 매뉴얼과 맞을 때만 MATCHED, 나머지는 모두 '공식 설명 미확인'
//   모델은 매뉴얼 규격표의 모델명(official_manual_model)과 비교용 키가 같아야 함 - 비슷한 모델·줄 이름만으로는 맞다고 보지 않음
// 매뉴얼 내용을 그대로 돌려줄 뿐 진단·수리 안내로 바꾸지 않음
// 조회 실패도 미확인으로 돌려줌 - 따로 읽기 트랜잭션에서 읽어서, 실패해도 상세 조회·사장님 수정 트랜잭션은 그대로
// 다른 트랜잭션 안에서 부르지 말 것 - 따로 읽는 트랜잭션이 DB 연결을 하나 더 잡아서, 동시 요청이 몰리면
//   연결이 바닥나 서로 기다림. 문의 상세는 문의 트랜잭션이 끝난 뒤에 부름 (OfficialErrorCodeAttacher)
@Slf4j
@Service
public class OfficialErrorCodeLookup {

    // 비교할 때 무시하는 글자 - 띄어쓰기·하이픈·괄호·점·슬래시·밑줄·가운뎃점 (V4 model_key와 같은 규칙)
    private static final Pattern IGNORED = Pattern.compile("[\\s()/._·-]");
    private static final Pattern CODE_IGNORED = Pattern.compile("[\\s_:-]");
    private static final Pattern THREE_DIGITS = Pattern.compile("\\d{3}");
    private static final String BOILER = "보일러";

    private final OfficialManualModelRepository modelRepository;
    private final OfficialManualErrorCodeRepository errorCodeRepository;
    private final TransactionTemplate readTransaction;

    public OfficialErrorCodeLookup(OfficialManualModelRepository modelRepository,
                                   OfficialManualErrorCodeRepository errorCodeRepository,
                                   PlatformTransactionManager transactionManager) {
        this.modelRepository = modelRepository;
        this.errorCodeRepository = errorCodeRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTransaction.setReadOnly(true);
    }

    // 확인된 값만 넘김 - 셋 중 하나라도 없으면(null) FIELD_NOT_CONFIRMED
    public OfficialErrorCodeResponse find(String manufacturer, String model, String errorCode) {
        if (isBlank(manufacturer) || isBlank(model) || isBlank(errorCode)) {
            return OfficialErrorCodeResponse.unconfirmed(Reason.FIELD_NOT_CONFIRMED);
        }
        String manufacturerKey = manufacturerKey(manufacturer);
        String modelKey = modelKey(manufacturer, model);
        String codeKey = errorCodeKey(errorCode);
        try {
            return readTransaction.execute(status -> read(manufacturerKey, modelKey, codeKey));
        } catch (RuntimeException e) {
            log.warn("공식 설명 조회 실패 - 미확인으로 보여 줌: {}", e.getClass().getSimpleName());
            return OfficialErrorCodeResponse.unconfirmed(Reason.LOOKUP_FAILED);
        }
    }

    private OfficialErrorCodeResponse read(String manufacturer, String modelKey, String code) {
        OfficialManualModel model = modelRepository.findByManufacturerAndModelKey(manufacturer, modelKey)
                .orElse(null);
        if (model == null) {
            return OfficialErrorCodeResponse.unconfirmed(Reason.MODEL_NOT_FOUND);
        }
        return errorCodeRepository.findByManualIdAndCode(model.getManual().getId(), code)
                .map(row -> OfficialErrorCodeResponse.matched(model, row))
                .orElseGet(() -> OfficialErrorCodeResponse.unconfirmed(Reason.CODE_NOT_FOUND));
    }

    // 제조사 - 무시하는 글자를 빼고 대문자로, 끝의 '보일러'는 뺌 (예: '귀뚜라미 보일러' → 귀뚜라미)
    public static String manufacturerKey(String manufacturer) {
        String key = IGNORED.matcher(manufacturer.toUpperCase(Locale.ROOT)).replaceAll("");
        return key.endsWith(BOILER) && key.length() > BOILER.length()
                ? key.substring(0, key.length() - BOILER.length()) : key;
    }

    // 모델 - 무시하는 글자를 빼고 대문자로, '에코'는 ECO로, '가스보일러'와 앞에 붙은 제조사 이름은 뺌
    // (예: '귀뚜라미 거꾸로 에코 콘덴싱 L20-15HFN 가스보일러' → 거꾸로ECO콘덴싱L2015HFN)
    public static String modelKey(String manufacturer, String model) {
        String key = IGNORED.matcher(model.toUpperCase(Locale.ROOT)).replaceAll("")
                .replace("에코", "ECO")
                .replace("가스" + BOILER, "");
        String maker = manufacturerKey(manufacturer);
        return !maker.isEmpty() && key.startsWith(maker) ? key.substring(maker.length()) : key;
    }

    // 오류코드 - 띄어쓰기·하이픈·밑줄·쌍점을 빼고 대문자로, 숫자 세 자리만 있으면 앞에 E를 붙임
    // (매뉴얼: 조절기 화면에는 숫자로 보임, 예: 001 → E001)
    // 두 자리 이하(예: 구형 코드 01)는 그대로 둠 - E001과 같은 코드로 보지 않음
    public static String errorCodeKey(String errorCode) {
        String key = CODE_IGNORED.matcher(errorCode.toUpperCase(Locale.ROOT)).replaceAll("");
        return THREE_DIGITS.matcher(key).matches() ? "E" + key : key;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
