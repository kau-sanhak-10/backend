package com.ilro.backend.domain.customer.entity;

import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 고객 - 업체별로 분리 (같은 번호도 업체가 다르면 다른 고객)
@Getter
@Entity
@Table(name = "customer")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @Column(length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Builder
    private Customer(Owner owner, String name, String phone) {
        this.owner = owner;
        this.name = name;
        this.phone = phone;
    }
}
