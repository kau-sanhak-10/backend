package com.ilro.backend.support;

import com.ilro.backend.infra.sms.SmsSendResult;
import com.ilro.backend.infra.sms.SmsSender;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 테스트용 문자 발송기 - 보낸 문자를 번호별로 들고 있어 테스트가 인증번호를 꺼내 씀 (로그에는 남기지 않음)
public class CapturingSmsSender implements SmsSender {

    private static final Pattern CODE = Pattern.compile("\\d{6}");

    private final Map<String, String> lastMessages = new ConcurrentHashMap<>();
    private final Set<String> failingPhones = ConcurrentHashMap.newKeySet();

    @Override
    public SmsSendResult send(String phone, String message) {
        if (failingPhones.contains(phone)) {
            return SmsSendResult.FAILED;
        }
        lastMessages.put(phone, message);
        return SmsSendResult.SENT;
    }

    public String lastCode(String phone) {
        String message = lastMessages.get(phone);
        if (message == null) {
            return null;
        }
        Matcher matcher = CODE.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }

    public boolean hasSentTo(String phone) {
        return lastMessages.containsKey(phone);
    }

    public void failFor(String phone) {
        failingPhones.add(phone);
    }
}
