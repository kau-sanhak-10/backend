package com.ilro.backend.infra.map;

// 지도 API 호출 실패 (infra) - 타임아웃·연결 실패·카카오 오류 응답(5xx·한도 초과 등)
// 결과가 없는 것(빈 목록)과 구분: 서비스가 받아서 503 MAP_UNAVAILABLE "잠시 후 다시"로 바꿈
// 원인 예외는 남기되, 메시지에 검색어·좌표·키를 넣지 않음 (로그로 새지 않게)
public class MapApiException extends RuntimeException {

    public MapApiException(String message, Throwable cause) {
        super(message, cause);
    }
}