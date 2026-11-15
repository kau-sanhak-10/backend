package com.ilro.backend.domain.address.dto;

import com.ilro.backend.infra.map.AddressSearchClient.Address;

import java.util.List;

// 주소 검색 결과 (고객 C-06·사장님 B-05) - 고른 항목의 address·lat·lng를 저장 요청에 그대로 보냄
// jibunAddress는 화면 참고용 (도로명만 있는 주소는 null)
public record AddressSearchResponse(List<Address> addresses) {
}