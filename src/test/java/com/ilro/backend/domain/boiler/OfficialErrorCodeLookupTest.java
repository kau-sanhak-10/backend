package com.ilro.backend.domain.boiler;

import com.ilro.backend.domain.boiler.service.OfficialErrorCodeLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 공식 설명 비교 규칙 (AI-14) - 제조사·모델·오류코드를 매뉴얼 자료와 비교하는 키
class OfficialErrorCodeLookupTest {

    @Test
    @DisplayName("모델 - 띄어쓰기·하이픈·괄호·대소문자 무시, 에코=ECO, 앞의 제조사 이름·'가스보일러'는 뺌")
    void modelKey() {
        assertThat(OfficialErrorCodeLookup.modelKey("귀뚜라미", "거꾸로 ECO 콘덴싱 L20-15HFN"))
                .isEqualTo("거꾸로ECO콘덴싱L2015HFN");
        assertThat(OfficialErrorCodeLookup.modelKey("귀뚜라미", "귀뚜라미 거꾸로 에코 콘덴싱 l20 15hfn 가스보일러"))
                .isEqualTo("거꾸로ECO콘덴싱L2015HFN");
        assertThat(OfficialErrorCodeLookup.modelKey("귀뚜라미", "거꾸로 ECO 콘덴싱-15H(C)"))
                .isEqualTo("거꾸로ECO콘덴싱15HC");
        assertThat(OfficialErrorCodeLookup.modelKey("귀뚜라미", "트윈알파eco E11")).isEqualTo("트윈알파ECOE11");
        // 제조사 이름이 앞에 있을 때만 뺌
        assertThat(OfficialErrorCodeLookup.modelKey("귀뚜라미", "L20-귀뚜라미")).isEqualTo("L20귀뚜라미");
    }

    @Test
    @DisplayName("오류코드 - 대문자로, 띄어쓰기·하이픈·쌍점 무시, 숫자 세 자리만이면 E를 붙이고 두 자리 이하는 그대로")
    void errorCodeKey() {
        assertThat(OfficialErrorCodeLookup.errorCodeKey("E001")).isEqualTo("E001");
        assertThat(OfficialErrorCodeLookup.errorCodeKey(" e-001 ")).isEqualTo("E001");
        assertThat(OfficialErrorCodeLookup.errorCodeKey("E:297")).isEqualTo("E297");
        assertThat(OfficialErrorCodeLookup.errorCodeKey("297")).isEqualTo("E297");
        assertThat(OfficialErrorCodeLookup.errorCodeKey("01")).isEqualTo("01");
        assertThat(OfficialErrorCodeLookup.errorCodeKey("1")).isEqualTo("1");
        assertThat(OfficialErrorCodeLookup.errorCodeKey("1001")).isEqualTo("1001");
    }

    @Test
    @DisplayName("제조사 - 띄어쓰기·대소문자 무시, 끝의 '보일러'는 뺌")
    void manufacturerKey() {
        assertThat(OfficialErrorCodeLookup.manufacturerKey("귀뚜라미")).isEqualTo("귀뚜라미");
        assertThat(OfficialErrorCodeLookup.manufacturerKey(" 귀뚜라미 보일러")).isEqualTo("귀뚜라미");
        assertThat(OfficialErrorCodeLookup.manufacturerKey("보일러")).isEqualTo("보일러");
        assertThat(OfficialErrorCodeLookup.manufacturerKey("Kd Navien")).isEqualTo("KDNAVIEN");
    }
}
