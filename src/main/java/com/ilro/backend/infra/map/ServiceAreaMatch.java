package com.ilro.backend.infra.map;

// 방문 주소가 업체 서비스 지역 안인지 - 지도가 판별할 수 없다고 하면 UNKNOWN (명세 3.5 OUT_OF_SERVICE_AREA: 지역 밖·판별 불가)
// 지도 장애는 UNKNOWN이 아니라 예외 (MapClient 약속)
public enum ServiceAreaMatch {
    INSIDE,
    OUTSIDE,
    UNKNOWN
}
