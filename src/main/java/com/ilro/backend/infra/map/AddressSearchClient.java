package com.ilro.backend.infra.map;

import java.math.BigDecimal;
import java.util.List;

// 주소·지역 검색 (BE-15) - 고객 방문 주소·업체 출발지의 좌표, 사장님 서비스 지역 코드를 얻음
// 검색 결과가 없으면 빈 목록 (오류 아님), 지도 장애·타임아웃은 MapUnavailableException
public interface AddressSearchClient {

    // 주소 검색 - 도로명·지번 주소와 좌표, 최대 size개
    List<Address> searchAddress(String query, int size);

    // 지역 검색 - 시군구 단위 (regionCode: 법정동 코드 앞 5자리), 최대 size개
    List<Region> searchRegion(String query, int size);

    // jibunAddress: 도로명 주소만 있는 결과도 있어 null일 수 있음
    record Address(String address, String jibunAddress, BigDecimal lat, BigDecimal lng) {
    }

    // regionName: 화면 표시용 (예: 고양시 덕양구)
    record Region(String regionCode, String regionName) {
    }
}