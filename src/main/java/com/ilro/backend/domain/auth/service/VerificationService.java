package com.ilro.backend.domain.auth.service;

import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.admin.repository.AdminRepository;
import com.ilro.backend.domain.auth.dto.ConfirmVerificationResponse;
import com.ilro.backend.domain.auth.dto.SendVerificationRequest;
import com.ilro.backend.domain.auth.dto.SendVerificationResponse;
import com.ilro.backend.domain.auth.entity.AuthTokenScope;
import com.ilro.backend.domain.auth.entity.AuthTokenType;
import com.ilro.backend.domain.auth.entity.PhoneVerification;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.entity.VerificationSendStatus;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.auth.repository.PhoneVerificationRepository;
import com.ilro.backend.domain.inquiry.entity.Inquiry;
import com.ilro.backend.domain.inquiry.exception.InquiryErrorCode;
import com.ilro.backend.domain.inquiry.repository.InquiryRepository;
import com.ilro.backend.domain.inquiry.service.InquiryAccess;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.KstTime;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import com.ilro.backend.global.exception.RetryAfter;
import com.ilro.backend.infra.sms.SmsSendResult;
import com.ilro.backend.infra.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// 6자리 문자 인증 (명세 5.1) - 사장님·운영자 로그인(BE-02), 고객 번호 확인·상담 복구·예약 조회(BE-18)
// 목적마다 대상이 정해짐: 사장님 로그인은 그 업체, 예약 전 번호 확인은 문의 토큰의 문의, 상담 복구·예약 조회는 링크의 업체
// 고객 인증으로는 사장님 토큰을 만들지 않고, 고객 토큰은 인증한 번호 + 그 업체 + 그 목적으로만 쓰임
// 발송 실패·틀린 입력도 기록이 남아야 하므로(누적 제한·시도 횟수) 저장을 먼저 끝내고 트랜잭션 밖에서 오류를 던짐
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 5;
    // 번호별 최근 1시간 5회·24시간 10회, IP별 최근 1시간 30회 - 인증 성공·화면 재접속으로 줄지 않음
    static final List<SendLimit> PHONE_LIMITS = List.of(
            new SendLimit(Duration.ofHours(1), 5), new SendLimit(Duration.ofHours(24), 10));
    static final SendLimit IP_LIMIT = new SendLimit(Duration.ofHours(1), 30);

    private final PhoneVerificationRepository verificationRepository;
    private final OwnerRepository ownerRepository;
    private final InquiryRepository inquiryRepository;
    private final InquiryAccess inquiryAccess;
    private final AdminRepository adminRepository;
    private final OwnerAccessPolicy ownerAccessPolicy;
    private final AuthTokenService authTokenService;
    private final VerificationCodes verificationCodes;
    private final SmsSender smsSender;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // inquiryToken: Authorization 헤더의 토큰 - 예약 전 번호 확인(CUSTOMER_VERIFY)에만 씀, 없으면 null
    public SendVerificationResponse send(SendVerificationRequest request, String requestIp, String inquiryToken) {
        String phone = PhoneNumbers.normalize(request.phone());
        if (!PhoneNumbers.isMobile(phone)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("phone", "휴대전화번호 형식이 아닙니다")));
        }
        // 문의 토큰·링크 확인은 발송 제한보다 먼저 - 토큰 없이 남의 문의로 문자를 보내지 못하게
        Long inquiryId = request.purpose() == VerificationPurpose.CUSTOMER_VERIFY ? inquiryOf(inquiryToken) : null;
        String linkToken = isLinkPurpose(request.purpose()) ? requireLinkToken(request.linkToken()) : null;
        PhoneVerification verification = transactionTemplate.execute(
                status -> issue(request.purpose(), phone, requestIp, inquiryId, linkToken));
        if (verification.getSendStatus() == VerificationSendStatus.FAILED) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_SEND_FAILED);
        }
        return new SendVerificationResponse(verification.getId(), KstTime.of(verification.getExpiresAt()),
                KstTime.of(verification.getCreatedAt().plus(RESEND_INTERVAL).plusSeconds(1)));
    }

    public ConfirmVerificationResponse confirm(Long verificationId, String code) {
        ConfirmOutcome outcome = transactionTemplate.execute(status -> verify(verificationId, code));
        if (outcome.error() != null) {
            throw outcome.error();
        }
        return outcome.response();
    }

    private PhoneVerification issue(VerificationPurpose purpose, String phone, String requestIp, Long inquiryId,
                                    String linkToken) {
        // 같은 번호로 동시에 들어온 요청이 둘 다 제한 검사를 통과하지 않게 번호 단위로 줄 세움
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> {
        }, "phone_verification:" + phone);
        // 같은 IP에서 번호만 바꿔 동시에 보낸 요청이 IP 한도를 함께 통과하지 않게 IP 단위로도 줄 세움
        // 모든 요청이 번호 → IP 순서로 잠그므로 서로 물고 기다리지 않음 (IP를 모르면 한도 검사처럼 건너뜀)
        if (requestIp != null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> {
            }, "phone_verification_ip:" + requestIp);
        }
        Instant now = clock.instant();
        checkSendLimits(purpose, phone, requestIp, now);

        Target target = switch (purpose) {
            // 등록·승인된 업체 번호에만 보냄. 아니면 문자 없이 이유를 알려 줌 (명세 7.2 8번)
            case OWNER_LOGIN -> Target.owner(ownerAccessPolicy.check(ownerRepository.findByPhone(phone)
                    .orElseThrow(() -> new BusinessException(AuthErrorCode.OWNER_NOT_REGISTERED))));
            case ADMIN_LOGIN -> {
                adminRepository.findByPhone(phone)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
                yield Target.NONE;
            }
            case CUSTOMER_VERIFY -> Target.inquiry(verifiableInquiry(inquiryId, phone));
            // 이 번호의 문의·예약이 그 업체에 있는지는 보내기 전에 알려 주지 않음 (번호만으로 상담 여부를 알 수 없게)
            case INQUIRY_ACCESS, RESERVATION_LOOKUP -> Target.owner(linkOwner(linkToken));
            default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("purpose", "아직 지원하지 않는 인증 목적입니다")));
        };

        String code = verificationCodes.generate();
        SmsSendResult sendResult = sendSms(phone, code);
        if (sendResult != SmsSendResult.SENT) {
            log.warn("Verification SMS failed: purpose={}, phone={}", purpose, PhoneNumbers.mask(phone));
        }
        return verificationRepository.save(PhoneVerification.builder()
                .phone(phone)
                .purpose(purpose)
                .codeHash(verificationCodes.hash(phone, purpose, code))
                .sendStatus(sendResult == SmsSendResult.SENT ? VerificationSendStatus.SENT : VerificationSendStatus.FAILED)
                .expiresAt(now.plus(CODE_TTL))
                .targetOwner(target.owner())
                .targetInquiry(target.inquiry())
                .requestIp(requestIp)
                .build());
    }

    // 예약 전 번호 확인은 문의 토큰으로만 - 사장님·고객(cus_) 토큰은 403
    private Long inquiryOf(String inquiryToken) {
        AuthPrincipal principal = authTokenService.authenticate(inquiryToken);
        if (principal.type() != AuthTokenType.INQUIRY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return principal.inquiryId();
    }

    private static boolean isLinkPurpose(VerificationPurpose purpose) {
        return purpose == VerificationPurpose.INQUIRY_ACCESS || purpose == VerificationPurpose.RESERVATION_LOOKUP;
    }

    private static String requireLinkToken(String linkToken) {
        if (linkToken == null || linkToken.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("linkToken", "필수 값입니다")));
        }
        return linkToken;
    }

    // 그 문의의 방문정보 번호로만 보냄 - 종료 409·업체 이용 불가 403·필수 동의 없음 403
    private Inquiry verifiableInquiry(Long inquiryId, String phone) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        inquiryAccess.checkWritable(inquiry);
        if (inquiry.getCustomer() == null || !inquiry.getCustomer().getPhone().equals(phone)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    List.of(new FieldErrorResponse("phone", "방문정보에 적은 휴대전화번호로 인증해 주세요")));
        }
        return inquiry;
    }

    // 접수 링크의 업체 - 없거나 승인 전·탈퇴면 404 (이용 제한 중이어도 지난 상담·예약은 볼 수 있게 보냄)
    private Owner linkOwner(String linkToken) {
        return ownerRepository.findByLinkToken(linkToken)
                .filter(Owner::isIntakeLinkValid)
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INTAKE_LINK_INVALID));
    }

    private void checkSendLimits(VerificationPurpose purpose, String phone, String requestIp, Instant now) {
        verificationRepository.findFirstByPhoneAndPurposeOrderByIdDesc(phone, purpose)
                .map(last -> Duration.between(now, last.getCreatedAt().plus(RESEND_INTERVAL)))
                .filter(Duration::isPositive)
                .ifPresent(wait -> {
                    throw new BusinessException(AuthErrorCode.VERIFICATION_RESEND_TOO_SOON, retryAfter(wait));
                });

        List<Instant> phoneSends = verificationRepository.findSendTimesByPhone(phone, now.minus(Duration.ofHours(24)));
        for (SendLimit limit : PHONE_LIMITS) {
            limit.waitTime(phoneSends, now).ifPresent(wait -> {
                throw new BusinessException(AuthErrorCode.VERIFICATION_SEND_LIMITED, retryAfter(wait));
            });
        }
        if (requestIp != null) {
            List<Instant> ipSends = verificationRepository.findSendTimesByIp(requestIp, now.minus(IP_LIMIT.window()));
            IP_LIMIT.waitTime(ipSends, now).ifPresent(wait -> {
                throw new BusinessException(AuthErrorCode.VERIFICATION_SEND_LIMITED, retryAfter(wait));
            });
        }
    }

    // 문자 본문·번호는 로그에 남기지 않음 (명세 1.8)
    private SmsSendResult sendSms(String phone, String code) {
        try {
            return smsSender.send(phone, "[일로] 인증번호는 " + code + "입니다. 5분 안에 입력해 주세요.");
        } catch (RuntimeException e) {
            log.warn("SMS sender threw {}", e.getClass().getSimpleName());
            return SmsSendResult.FAILED;
        }
    }

    private ConfirmOutcome verify(Long verificationId, String code) {
        PhoneVerification verification = verificationRepository.findByIdForUpdate(verificationId)
                .filter(found -> found.getSendStatus() == VerificationSendStatus.SENT)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.VERIFICATION_NOT_FOUND));
        if (verification.isVerified()) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_ALREADY_USED);
        }
        Instant now = clock.instant();
        // 새로 보낸 인증번호가 있으면 이전 번호는 무효
        boolean replaced = verificationRepository.existsByPhoneAndPurposeAndSendStatusAndIdGreaterThan(
                verification.getPhone(), verification.getPurpose(), VerificationSendStatus.SENT, verification.getId());
        if (verification.isExpired(now) || replaced) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_EXPIRED);
        }
        if (verification.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!verificationCodes.matches(verification.getPhone(), verification.getPurpose(), code,
                verification.getCodeHash())) {
            int attemptsLeft = verification.recordFailedAttempt(MAX_ATTEMPTS);
            return ConfirmOutcome.failed(attemptsLeft > 0
                    ? new BusinessException(AuthErrorCode.VERIFICATION_CODE_MISMATCH, Map.of("attemptsLeft", attemptsLeft))
                    : new BusinessException(AuthErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED));
        }
        verification.markVerified(now);
        return ConfirmOutcome.succeeded(result(verification, now));
    }

    // 고객 인증으로는 절대 사장님 토큰을 발급하지 않음 - 목적별로만 발급
    // 여기서 오류를 던지면 인증 성공 기록도 되돌아가 같은 인증번호로 다시 확인할 수 있음
    private ConfirmVerificationResponse result(PhoneVerification verification, Instant now) {
        return switch (verification.getPurpose()) {
            case OWNER_LOGIN -> {
                // 보낸 뒤 로그인 번호가 바뀌었거나 이용 제한이 됐으면 로그인하지 않음
                Owner owner = verification.getTargetOwner();
                if (owner == null || !verification.getPhone().equals(owner.getPhone())) {
                    throw new BusinessException(AuthErrorCode.OWNER_NOT_REGISTERED);
                }
                ownerAccessPolicy.check(owner);
                yield ConfirmVerificationResponse.ownerLogin(authTokenService.issueOwnerToken(owner), owner);
            }
            case ADMIN_LOGIN -> {
                Admin admin = adminRepository.findByPhone(verification.getPhone())
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
                yield ConfirmVerificationResponse.adminLogin(authTokenService.issueAdminToken(admin), admin);
            }
            case CUSTOMER_VERIFY -> {
                Inquiry inquiry = markPhoneVerified(verification, now);
                yield ConfirmVerificationResponse.customerVerified(inquiry.getId(), now);
            }
            case INQUIRY_ACCESS, RESERVATION_LOOKUP -> {
                Owner owner = verification.getTargetOwner();
                if (owner == null || !owner.isIntakeLinkValid()) {
                    throw new BusinessException(InquiryErrorCode.INTAKE_LINK_INVALID);
                }
                AuthTokenScope scope = AuthTokenScope.valueOf(verification.getPurpose().name());
                yield ConfirmVerificationResponse.customerToken(verification.getPurpose(),
                        authTokenService.issueCustomerToken(owner, verification.getPhone(), scope));
            }
            default -> throw new BusinessException(AuthErrorCode.VERIFICATION_NOT_FOUND);
        };
    }

    // 문의 행을 잠근 뒤 기록 - 보낸 뒤 방문정보 번호가 바뀌었으면 이 인증은 그 문의에 쓸 수 없음
    // 방문정보 저장(VisitInfoWriter)도 같은 행 잠금이라, 번호를 바꾸는 저장과 섞이지 않음
    private Inquiry markPhoneVerified(PhoneVerification verification, Instant now) {
        Inquiry inquiry = inquiryRepository.findByIdForUpdate(verification.getTargetInquiry().getId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        inquiryAccess.checkOpen(inquiry);
        if (inquiry.getCustomer() == null || !inquiry.getCustomer().getPhone().equals(verification.getPhone())) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_PURPOSE_MISMATCH);
        }
        inquiry.markPhoneVerified(now);
        return inquiry;
    }

    private static RetryAfter retryAfter(Duration wait) {
        long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
        return new RetryAfter(Math.max(1, seconds));
    }

    // 인증 대상 - 업체 또는 문의 (운영자 로그인은 없음)
    private record Target(Owner owner, Inquiry inquiry) {

        static final Target NONE = new Target(null, null);

        static Target owner(Owner owner) {
            return new Target(owner, null);
        }

        static Target inquiry(Inquiry inquiry) {
            return new Target(null, inquiry);
        }
    }

    // 확인 결과 - 틀린 입력은 시도 횟수를 저장한 뒤 오류로 돌려줌
    private record ConfirmOutcome(ConfirmVerificationResponse response, BusinessException error) {

        static ConfirmOutcome succeeded(ConfirmVerificationResponse response) {
            return new ConfirmOutcome(response, null);
        }

        static ConfirmOutcome failed(BusinessException error) {
            return new ConfirmOutcome(null, error);
        }
    }
}
