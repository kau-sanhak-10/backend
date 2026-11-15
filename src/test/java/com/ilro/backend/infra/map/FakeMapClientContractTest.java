package com.ilro.backend.infra.map;

// 가짜 지도(로컬·개발 서버)도 지도 약속을 지키는지
class FakeMapClientContractTest extends MapClientContract {

    private final FakeMapClient map = new FakeMapClient();

    @Override
    protected MapClient map() {
        return map;
    }
}
