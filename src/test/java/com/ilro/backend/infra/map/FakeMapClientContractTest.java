package com.ilro.backend.infra.map;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// 실제 카카오 지도도 지도 약속(MapClientContract)을 지키는지 - 진짜 카카오를 부름
// KAKAO_REST_API_KEY 환경변수가 있을 때만 돌림 (CI·키 없는 로컬은 건너뜀, 카카오 호출 한도도 아낌)
@EnabledIfEnvironmentVariable(named = "KAKAO_REST_API_KEY", matches = ".+")
class KakaoMapClientContractTest extends MapClientContract {

    private final KakaoMapClient map = new KakaoMapClient("https://dapi.kakao.com",
            "https://apis-navi.kakaomobility.com", System.getenv("KAKAO_REST_API_KEY"));

    @Override
    protected MapClient map() {
        return map;
    }
}