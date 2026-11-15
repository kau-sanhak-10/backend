package com.ilro.backend.infra.map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

// 가짜 주소·지역 검색 - local·테스트에서 카카오 키 없이 화면·API를 만들 수 있게 (실제 주소 검색 아님)
// 정해 둔 몇 곳 중 검색어가 들어간 것만 돌려줌, 아무것도 안 맞으면 빈 목록
// dev·prod는 KakaoAddressSearchClient
@Component
@Profile("!dev & !prod")
public class FakeAddressSearchClient implements AddressSearchClient {

    private static final List<Address> ADDRESSES = List.of(
            new Address("경기 고양시 덕양구 항공대학로 76", "경기 고양시 덕양구 화전동 200-1",
                    new BigDecimal("37.6000000"), new BigDecimal("126.8650000")),
            new Address("경기 고양시 덕양구 고양시청로 10", "경기 고양시 덕양구 주교동 601",
                    new BigDecimal("37.6584000"), new BigDecimal("126.8320000")),
            new Address("서울 중구 세종대로 110", "서울 중구 태평로1가 31",
                    new BigDecimal("37.5666000"), new BigDecimal("126.9784000")));

    private static final List<Region> REGIONS = List.of(
            new Region("41281", "경기 고양시 덕양구"),
            new Region("41285", "경기 고양시 일산동구"),
            new Region("11140", "서울 중구"),
            new Region("26110", "부산 중구"));

    @Override
    public List<Address> searchAddress(String query, int size) {
        return ADDRESSES.stream()
                .filter(a -> a.address().contains(query) || a.jibunAddress().contains(query))
                .limit(size)
                .toList();
    }

    @Override
    public List<Region> searchRegion(String query, int size) {
        return REGIONS.stream()
                .filter(r -> r.regionName().contains(query))
                .limit(size)
                .toList();
    }
}