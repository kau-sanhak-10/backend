package com.ilro.backend.domain.auth.repository;

import com.ilro.backend.domain.auth.entity.PhoneVerification;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.entity.VerificationSendStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    // 확인할 때 시도 횟수·사용 여부를 동시에 바꾸지 못하게 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PhoneVerification v where v.id = :id")
    Optional<PhoneVerification> findByIdForUpdate(@Param("id") Long id);

    // 같은 번호·목적의 가장 최근 발송 (재발송 간격)
    Optional<PhoneVerification> findFirstByPhoneAndPurposeOrderByIdDesc(String phone, VerificationPurpose purpose);

    // 누적 발송 제한 - 성공·실패 발송 모두 셈, 인증 성공으로 줄지 않음
    @Query("select v.createdAt from PhoneVerification v where v.phone = :phone and v.createdAt > :since")
    List<Instant> findSendTimesByPhone(@Param("phone") String phone, @Param("since") Instant since);

    @Query("select v.createdAt from PhoneVerification v where v.requestIp = :ip and v.createdAt > :since")
    List<Instant> findSendTimesByIp(@Param("ip") String ip, @Param("since") Instant since);

    // 새로 보내면 이전 번호는 무효 - 이보다 나중에 보낸 같은 번호·목적 인증이 있는지
    boolean existsByPhoneAndPurposeAndSendStatusAndIdGreaterThan(String phone, VerificationPurpose purpose,
                                                                 VerificationSendStatus sendStatus, Long id);
}
