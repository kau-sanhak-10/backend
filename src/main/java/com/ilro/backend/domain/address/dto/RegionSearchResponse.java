package com.ilro.backend.domain.address.dto;

import com.ilro.backend.infra.map.AddressSearchClient.Region;

import java.util.List;

// 지역 검색 결과 (사장님 B-06) - 고른 항목의 regionCode·regionName을 서비스 지역 저장(PUT /owner/service-areas)에 그대로 보냄
// regionCode: 시군구 5자리, regionName: "경기 고양시 덕양구"
public record RegionSearchResponse(List<Region> regions) {
}