package com.ilro.backend.domain.admin.service;

import com.ilro.backend.domain.admin.dto.AdminOwnerListResponse;
import com.ilro.backend.domain.admin.dto.AdminOwnerResponse;
import com.ilro.backend.domain.admin.dto.ChangeLoginPhoneRequest;
import com.ilro.backend.domain.admin.dto.ChangeOwnerStatusRequest;
import com.ilro.backend.domain.admin.dto.RegisterOwnerRequest;
import com.ilro.backend.domain.admin.dto.RejectOwnerRequest;
import com.ilro.backend.domain.admin.entity.Admin;
import com.ilro.backend.domain.auth.entity.PhoneVerification;
import com.ilro.backend.domain.auth.entity.VerificationPurpose;
import com.ilro.backend.domain.auth.exception.AuthErrorCode;
import com.ilro.backend.domain.auth.repository.PhoneVerificationRepository;
import com.ilro.backend.domain.auth.service.AuthTokenService;
import com.ilro.backend.domain.owner.entity.ApprovalStatus;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.entity.OwnerStatus;
import com.ilro.backend.domain.owner.exception.OwnerErrorCode;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.domain.owner.service.IntakeLinkTokens;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.common.util.AccessLog;
import com.ilro.backend.global.common.util.PhoneNumbers;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// 운영팀의 업체·계정 관리 (O-01·O-02·O-03, 명세 4.8·5.8, BE-17)
// 신청 심사(approval_status)·운영 상태(status)·로그인 연결(phone)·자동 예약은 서로 독립 - 하나를 바꿔도 다른 것을 켜지 않음
//   자동 예약은 사장님만 켬 (승인만으로 켜지지 않음)
// 어떤 변경도 그 업체의 예약·문의·고객을 지우거나 바꾸지 않음. 이용 제한·번호 변경은 사장님 로그인 토큰만 끊음
// 응답에는 업체·계정 상태만 - 승인 대기·거절 업체든 아니든 고객·예약 내용은 열지 않음
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOwnerService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    // 번호 확인(OWNER_SIGNUP)을 마친 뒤 이 안에 등록·번호 변경을 해야 함
    static final Duration SIGNUP_VERIFICATION_WINDOW = Duration.ofMinutes(30);

    private final OwnerRepository ownerRepository;
    private final PhoneVerificationRepository verificationRepository;
    private final AuthTokenService authTokenService;
    private final IntakeLinkTokens linkTokens;
    private final AdminAccess adminAccess;
    private final Clock clock;

    // B-01·O-02 등록 → 승인 대기. 같은 로그인 번호가 있으면 409 OWNER_PHONE_DUPLICATED
    // 접수 링크 토큰은 칸이 필수라 미리 만들어 두지만, 승인 전에는 없는 링크로 봄 (Owner.isIntakeLinkValid)
    @Transactional
    public AdminOwnerResponse register(AuthPrincipal principal, RegisterOwnerRequest request) {
        adminAccess.require(principal);
        String phone = mobilePhone(request.phone(), "phone");
        String contactPhone = PhoneNumbers.normalize(request.contactPhone());
        if (contactPhone != null && !PhoneNumbers.isContactNumber(contactPhone)) {
            throw invalid("contactPhone", "전화번호 형식이 아닙니다");
        }
        requireSignupVerification(request.phoneVerificationId(), phone);
        if (ownerRepository.existsByPhone(phone)) {
            throw new BusinessException(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
        }
        // 동시에 같은 번호를 등록하면 uq_owner_phone → 409 OWNER_PHONE_DUPLICATED (ConstraintErrorMapper)
        Owner owner = ownerRepository.saveAndFlush(Owner.builder()
                .businessName(request.businessName().strip())
                .ownerName(strip(request.ownerName()))
                .phone(phone)
                .linkToken(linkTokens.generate())
                .contactPhone(contactPhone)
                .address(strip(request.address()))
                .serviceDescription(strip(request.serviceDescription()))
                .build());
        record(principal, "REGISTER", owner);
        return AdminOwnerResponse.from(owner);
    }

    // O-01 목록 - 보낸 조건만 거름. q는 상호·대표자 이름(일부), 숫자·하이픈만이면 로그인 번호(일부)
    @Transactional(readOnly = true)
    public AdminOwnerListResponse list(AuthPrincipal principal, ApprovalStatus approvalStatus, OwnerStatus status,
                                       String q, Integer page, Integer size) {
        adminAccess.require(principal);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        List<FieldErrorResponse> errors = new ArrayList<>();
        if (pageNumber < 0 || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            errors.add(new FieldErrorResponse("page", "0 이상의 쪽 번호를 보내 주세요"));
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            errors.add(new FieldErrorResponse("size", "1 ~ " + MAX_PAGE_SIZE + " 사이여야 합니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        return AdminOwnerListResponse.from(ownerRepository.findAll(matching(approvalStatus, status, q),
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id"))));
    }

    // O-01 상세
    @Transactional(readOnly = true)
    public AdminOwnerResponse get(AuthPrincipal principal, Long ownerId) {
        adminAccess.require(principal);
        return AdminOwnerResponse.from(ownerRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)));
    }

    // O-02 승인 - 심사 대기(PENDING)만. 승인해도 운영 상태는 READY 그대로이고 자동 예약도 꺼져 있음
    @Transactional
    public AdminOwnerResponse approve(AuthPrincipal principal, Long ownerId) {
        Admin admin = adminAccess.require(principal);
        Owner owner = pendingOwner(ownerId);
        owner.approve(admin, clock.instant());
        record(principal, "APPROVE", owner);
        return AdminOwnerResponse.from(owner);
    }

    // O-02 거절 - 사유 필수 (요청 검증), 심사 대기만
    @Transactional
    public AdminOwnerResponse reject(AuthPrincipal principal, Long ownerId, RejectOwnerRequest request) {
        Admin admin = adminAccess.require(principal);
        Owner owner = pendingOwner(ownerId);
        owner.reject(admin, request.reason().strip(), clock.instant());
        record(principal, "REJECT", owner);
        return AdminOwnerResponse.from(owner);
    }

    // O-02 운영 상태 READY·ACTIVE·RESTRICTED - 승인된 업체만 (아니면 409 OWNER_NOT_APPROVED)
    // 이용 제한이 되면 그 업체의 사장님 로그인 토큰을 모두 끊음 (명세 1.5). 예약·문의·고객은 그대로
    // 탈퇴(WITHDRAWN)는 여기서 만들 수도 되돌릴 수도 없음
    @Transactional
    public AdminOwnerResponse changeStatus(AuthPrincipal principal, Long ownerId, ChangeOwnerStatusRequest request) {
        adminAccess.require(principal);
        if (request.status() == OwnerStatus.WITHDRAWN) {
            throw invalid("status", "READY·ACTIVE·RESTRICTED 중 하나여야 합니다");
        }
        Owner owner = lock(ownerId);
        if (owner.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new BusinessException(OwnerErrorCode.OWNER_NOT_APPROVED);
        }
        requireNotWithdrawn(owner);
        if (owner.getStatus() != request.status()) {
            owner.changeStatus(request.status());
            if (request.status() == OwnerStatus.RESTRICTED) {
                authTokenService.revokeOwnerTokens(owner.getId());
            }
            record(principal, "STATUS_" + request.status(), owner);
        }
        return AdminOwnerResponse.from(owner);
    }

    // O-03 로그인 번호 변경 - SUPER 운영자만. 새 번호로 받은 OWNER_SIGNUP 인증이 있어야 함
    // 번호만 바꾸고 업체·예약·고객은 그대로, 기존 사장님 로그인 토큰은 모두 끊음. 로그에 전후 번호를 남기지 않음
    @Transactional
    public AdminOwnerResponse changeLoginPhone(AuthPrincipal principal, Long ownerId, ChangeLoginPhoneRequest request) {
        adminAccess.requireSuper(principal);
        String newPhone = mobilePhone(request.newPhone(), "newPhone");
        Owner owner = lock(ownerId);
        requireNotWithdrawn(owner);
        requireSignupVerification(request.phoneVerificationId(), newPhone);
        if (ownerRepository.existsByPhone(newPhone)) {
            throw new BusinessException(OwnerErrorCode.OWNER_PHONE_DUPLICATED);
        }
        owner.changeLoginPhone(newPhone);
        // 겹침(uq_owner_phone)을 여기서 바로 확인 → 409 OWNER_PHONE_DUPLICATED
        ownerRepository.flush();
        authTokenService.revokeOwnerTokens(owner.getId());
        record(principal, "LOGIN_PHONE", owner);
        return AdminOwnerResponse.from(owner);
    }

    // 심사 대기 업체를 잠가서 가져옴 - 이미 승인·거절했으면 409 OWNER_ALREADY_REVIEWED (지금 심사 상태와 함께)
    private Owner pendingOwner(Long ownerId) {
        Owner owner = lock(ownerId);
        if (owner.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new BusinessException(OwnerErrorCode.OWNER_ALREADY_REVIEWED,
                    Collections.singletonMap("approvalStatus", owner.getApprovalStatus()));
        }
        return owner;
    }

    // 업체 행을 잠금 - 두 운영자가 동시에 심사·상태 변경을 해도 차례로 (예약 확정 등 일정 쓰기와도 같은 잠금)
    private Owner lock(Long ownerId) {
        return ownerRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    // 탈퇴한 업체는 운영 상태·로그인 번호를 바꾸지 않음 - 403 OWNER_WITHDRAWN
    private static void requireNotWithdrawn(Owner owner) {
        if (owner.getStatus() == OwnerStatus.WITHDRAWN) {
            throw new BusinessException(AuthErrorCode.OWNER_WITHDRAWN);
        }
    }

    // 그 번호로 방금(30분 안) 마친 OWNER_SIGNUP 인증인지 - 없는 인증 404, 다른 번호·다른 목적·미확인·오래됨 403
    private void requireSignupVerification(Long verificationId, String phone) {
        PhoneVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.VERIFICATION_NOT_FOUND));
        Instant oldest = clock.instant().minus(SIGNUP_VERIFICATION_WINDOW);
        if (verification.getPurpose() != VerificationPurpose.OWNER_SIGNUP || !verification.getPhone().equals(phone)
                || !verification.isVerified() || verification.getVerifiedAt().isBefore(oldest)) {
            throw new BusinessException(AuthErrorCode.VERIFICATION_PURPOSE_MISMATCH);
        }
    }

    private static Specification<Owner> matching(ApprovalStatus approvalStatus, OwnerStatus status, String q) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (approvalStatus != null) {
                predicates.add(builder.equal(root.get("approvalStatus"), approvalStatus));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            String keyword = q == null ? "" : q.strip();
            if (!keyword.isEmpty()) {
                if (keyword.matches("[0-9-]+")) {
                    predicates.add(builder.like(root.get("phone"), contains(keyword.replace("-", "")), '\\'));
                } else {
                    String pattern = contains(keyword.toLowerCase(Locale.ROOT));
                    predicates.add(builder.or(
                            builder.like(builder.lower(root.get("businessName")), pattern, '\\'),
                            builder.like(builder.lower(root.get("ownerName")), pattern, '\\')));
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    // LIKE에 넣을 '포함' 무늬 - 검색어의 %·_·\ 는 글자 그대로
    private static String contains(String keyword) {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String mobilePhone(String raw, String field) {
        String phone = PhoneNumbers.normalize(raw);
        if (!PhoneNumbers.isMobile(phone)) {
            throw invalid(field, "휴대전화번호 형식이 아닙니다");
        }
        return phone;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.INVALID_INPUT, List.of(new FieldErrorResponse(field, message)));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    // 운영자가 업체 계정을 바꾼 기록 - 누가·무엇을·어느 업체에 (번호·사유 같은 값은 남기지 않음)
    private static void record(AuthPrincipal principal, String action, Owner owner) {
        AccessLog.record(principal, "UPDATE", "owner", owner.getId());
        log.info("Admin owner change: action={}, ownerId={}, adminId={}", action, owner.getId(), principal.adminId());
    }
}
