package com.ilro.backend.infra.map;

// 방문 주소가 업체 서비스 지역 안인지 - 모르면 UNKNOWN (명세 3.5 OUT_OF_SERVICE_AREA: 지역 밖·판별 불가)
public enum ServiceAreaMatch {
    INSIDE,
    OUTSIDE,
    UNKNOWN
}
