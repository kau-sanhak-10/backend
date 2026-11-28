package com.ilro.backend.domain.owner.service;

import com.ilro.backend.domain.auth.entity.PhoneVerification;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.auth.repository.PhoneVerificationRepository;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.auth.service.OwnerAccessPolicy;
import com.ilro.backend.domain.inquiry.entity.InquiryStatus;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.owner.dto.OwnerWithdrawalRequest;
import com.ilro.backend.domain.owner.dto.OwnerWithdrawalResponse;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.reservation.entity.ReservationStatus;
import com.ilro.backend.domain.reservation.repository.ReservationRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// 사장님 탈퇴 요청 (B-04, 명세 3.2·4.4·5.1, BE-16) - 예약 취소와 다름: 예약·문의를 지우거나 취소하지 않음
// 1) 업체 행을 잠금 - 예약 확정·수동 예약·변경 수락과 차례로 (그사이 새 예약이 끼어들지 않게)
// 2) 다시 인증: 방금(10분 안) 이 업체 로그인 번호로 받은 OWNER_LOGIN 인증
// 3) 처리하지 않은 문의·끝나지 않은 예약이 있으면 422 WITHDRAWAL_BLOCKED (개수) - 사장님이 정리한 뒤 다시
// 4) WITHDRAWN + 자동 예약 끄기 + 이 업체 로그인 토큰 모두 끊기 - 접수 링크·로그인 문자도 막힘 (OwnerAccessPolicy)
@Service
@RequiredArgsConstructor
public class OwnerWithdrawalService {

    // 다시 인증으로 인정하는 시간 - 인증을 마친 뒤 이 안에 탈퇴 요청
    static final Duration REAUTH_WINDOW = Duration.ofMinutes(10);

    // 처리하지 않은 문의 - 예약으로 이어졌거나(BOOKED) 끝난 문의(서비스 불가·종료)는 셈하지 않음
    static final Set<InquiryStatus> OPEN_INQUIRY = EnumSet.of(
            InquiryStatus.COLLECTING, InquiryStatus.SCHEDULING, InquiryStatus.OWNER_CHECK);

    // 끝나지 않은 예약 - 지난 방문도 완료(COMPLETED) 처리 전이면 셈 (취소·완료는 셈하지 않음)
    static final Set<ReservationStatus> ACTIVE_RESERVATION = EnumSet.of(
            ReservationStatus.REQUESTED, ReservationStatus.CONFIRMED);

    private final OwnerRepository ownerRepository;
    private final OwnerAccessPolicy ownerAccessPolicy;
    private final PhoneVerificationRepository verificationRepository;
    private final InquiryRepository inquiryRepository;
    private final ReservationRepository reservationRepository;
    private final AuthTokenService authTokenService;
    private final Clock clock;

    @Transactional
    public OwnerWithdrawalResponse withdraw(AuthPrincipal principal, OwnerWithdrawalRequest request) {
        Owner owner = ownerRepository.findByIdForUpdate(principal.ownerId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        // 잠근 뒤 다시 - 이미 탈퇴했거나 그사이 이용 제한이면 403
        ownerAccessPolicy.check(owner);
        Instant now = clock.instant();
        requireRecentReauth(owner, request.verificationId(), now);

        long openInquiries = inquiryRepository.countByOwnerIdAndStatusInAndDeletedAtIsNull(owner.getId(),
                OPEN_INQUIRY);
        long activeReservations = reservationRepository.countByOwnerIdAndStatusIn(owner.getId(),
                ACTIVE_RESERVATION);
        if (openInquiries > 0 || activeReservations > 0) {
            throw new BusinessException(AuthErrorCode.WITHDRAWAL_BLOCKED,
                    Map.of("openInquiries", openInquiries, "activeReservations", activeReservations));
        }

        owner.withdraw();
        authTokenService.revokeOwnerTokens(owner.getId());
        AccessLog.record(principal, "WITHDRAW", "owner", owner.getId());
        return new OwnerWithdrawalResponse(owner.getId(), owner.getStatus(), KstTime.of(now));
    }

    // 다른 업체의 인증은 없는 것처럼 404, 목적이 다르거나 로그인 번호가 바뀌었거나 아직·오래전 인증이면 403
    private void requireRecentReauth(Owner owner, Long verificationId, Instant now) {
        PhoneVerification verification = verificationRepository.findById(verificationId)
                .filter(found -> found.getTargetOwner() != null
                        && found.getTargetOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.VERIFICATION_NOT_FOUND));
        boolean recent = verification.isVerified() && !verification.getVerifiedAt().isBefore(now.minus(REAUTH_WINDOW));
        if (verification.getPurpose() != VerificationPurpose.OWNER_LOGIN
                || !verification.getPhone().equals(owner.getPhone()) || !recent) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_PURPOSE_MISMATCH);
        }
    }
}
