package com.ilro.backend.domain.admin.repository;

import com.ilro.backend.domain.admin.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    // phone은 숫자만으로 정규화한 값
    Optional<Admin> findByPhone(String phone);
}
