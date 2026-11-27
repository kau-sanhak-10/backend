package com.ilro.backend.support;

import com.ilro.backend.infra.push.WebPushResult;
import com.ilro.backend.infra.push.WebPushSender;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 테스트용 푸시 발송기 - 보낸 구독 주소를 기억하고, 주소별로 결과(GONE·FAILED)를 정할 수 있음
public class CapturingWebPushSender implements WebPushSender {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final Map<String, WebPushResult> results = new ConcurrentHashMap<>();

    @Override
    public WebPushResult send(String endpoint) {
        WebPushResult result = results.getOrDefault(endpoint, WebPushResult.SENT);
        if (result == WebPushResult.SENT) {
            sent.add(endpoint);
        }
        return result;
    }

    public boolean hasSentTo(String endpoint) {
        return sent.contains(endpoint);
    }

    public void answer(String endpoint, WebPushResult result) {
        results.put(endpoint, result);
    }
}