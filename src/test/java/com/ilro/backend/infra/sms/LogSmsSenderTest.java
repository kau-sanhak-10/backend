package com.ilro.backend.infra.sms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LogSmsSenderTest {
    @Test
    void doesNotLogRecipientOrVerificationCode(CapturedOutput output) {
        assertThat(new LogSmsSender().send("01012345678", "인증번호 927451"))
                .isEqualTo(SmsSendResult.SENT);
        assertThat(output.getAll()).contains("발송 요청 처리")
                .doesNotContain("01012345678", "927451", "인증번호");
    }
}
