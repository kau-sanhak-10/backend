package com.ilro.backend.domain.notification.service;

import com.ilro.backend.domain.notification.entity.NotifySettingType;
import com.ilro.backend.domain.owner.entity.Owner;
import com.ilro.backend.domain.owner.repository.OwnerRepository;
import com.ilro.backend.global.auth.AuthPrincipal;
import com.ilro.backend.global.common.response.FieldErrorResponse;
import com.ilro.backend.global.exception.BusinessException;
import com.ilro.backend.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 사장님 알림 유형별 푸시 수신 설정 (B-27, 명세 5.6, BE-14) - owner.notify_settings (JSONB)
// 저장한 적 없거나 빠진 유형은 켜짐 - 처음 쓰는 사장님도 알림을 놓치지 않게
// PUT은 4개 유형을 모두 보내야 함 (빠지거나 null이면 400, 모르는 키는 요청을 읽을 때 400)
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final OwnerRepository ownerRepository;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public Map<NotifySettingType, Boolean> get(AuthPrincipal principal) {
        return settingsOf(owner(principal));
    }

    @Transactional
    public Map<NotifySettingType, Boolean> update(AuthPrincipal principal, Map<NotifySettingType, Boolean> request) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        for (NotifySettingType type : NotifySettingType.values()) {
            if (request == null || request.get(type) == null) {
                errors.add(new FieldErrorResponse(type.name(), "true 또는 false를 보내 주세요"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, errors);
        }
        Map<NotifySettingType, Boolean> settings = new EnumMap<>(request);
        owner(principal).changeNotifySettings(jsonMapper.writeValueAsString(settings));
        return settings;
    }

    // 업체의 지금 설정 - 발송기(3단계)도 이걸로 유형별 수신 여부를 봄
    public Map<NotifySettingType, Boolean> settingsOf(Owner owner) {
        Map<NotifySettingType, Boolean> settings = new EnumMap<>(NotifySettingType.class);
        for (NotifySettingType type : NotifySettingType.values()) {
            settings.put(type, true);
        }
        String stored = owner.getNotifySettings();
        if (stored == null || stored.isBlank()) {
            return settings;
        }
        try {
            JsonNode node = jsonMapper.readTree(stored);
            for (NotifySettingType type : NotifySettingType.values()) {
                JsonNode value = node.get(type.name());
                if (value != null && value.isBoolean()) {
                    settings.put(type, value.booleanValue());
                }
            }
        } catch (JacksonException e) {
            // 저장된 값이 깨졌으면 모두 켜짐으로 - 알림을 놓치는 것보다 받는 쪽이 안전
            log.warn("Invalid notify_settings: ownerId={}", owner.getId());
        }
        return settings;
    }

    // 인터셉터가 사장님 토큰·업체 상태를 이미 확인함 - 여기서 없으면 토큰이 가리키는 업체가 사라진 것
    private Owner owner(AuthPrincipal principal) {
        return ownerRepository.findById(principal.ownerId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
    }
}