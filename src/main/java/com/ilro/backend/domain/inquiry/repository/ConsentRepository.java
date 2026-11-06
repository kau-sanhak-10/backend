package com.ilro.backend.domain.inquiry.repository;

import com.ilro.backend.domain.inquiry.entity.Consent;
import com.ilro.backend.domain.inquiry.entity.ConsentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {

    List<Consent> findByInquiryIdOrderByIdAsc(Long inquiryId);

    boolean existsByInquiryIdAndConsentType(Long inquiryId, ConsentType consentType);
}
