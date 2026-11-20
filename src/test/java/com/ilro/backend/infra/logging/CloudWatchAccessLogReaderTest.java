package com.ilro.backend.infra.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// CloudWatch 접근 기록 읽기 (O-09, BE-19) - 실제 AWS 없이 가짜 클라이언트로 요청·읽기·실패 처리를 확인
// 실제 CloudWatch 연결(권한·로그 그룹)은 Dev 배포 뒤에 따로 확인
class CloudWatchAccessLogReaderTest {

    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T00:00:00Z");
    private static final String GROUP = "/ilro/dev/app";

    private final CloudWatchLogsClient client = mock(CloudWatchLogsClient.class);
    private final CloudWatchAccessLogReader reader = new CloudWatchAccessLogReader(client, GROUP,
            JsonMapper.builder().build());

    @Test
    @DisplayName("JSON 로그 줄의 message에서 접근 기록을 읽고, 다음 쪽 토큰이 있으면 이어서 읽음")
    void readsJsonLinesAcrossPages() {
        when(client.filterLogEvents(any(FilterLogEventsRequest.class)))
                .thenReturn(page("next-1",
                        event(1_000, json("actor=OWNER:7 action=VIEW target=inquiry:101 result=OK")),
                        // 접근 기록 형식이 아닌 줄은 건너뜀
                        event(2_000, json("something else")),
                        // 대상 번호가 앞부분만 같은 줄(1010)은 걸러 냄
                        event(3_000, json("actor=OWNER:7 action=VIEW target=inquiry:1010 result=OK"))))
                .thenReturn(page(null,
                        // JSON이 아닌 줄도 형식이 맞으면 읽음
                        event(4_000, "actor=ADMIN:3 action=DELETE target=inquiry:101 result=FAILED")));

        AccessLogReader.Result result = reader.read(new AccessLogReader.Query("INQUIRY", 101L, FROM, TO));

        assertThat(result.truncated()).isFalse();
        assertThat(result.entries()).containsExactly(
                new AccessLogEntry(Instant.ofEpochMilli(1_000), "OWNER", 7L, "VIEW", "inquiry", 101L, "OK"),
                new AccessLogEntry(Instant.ofEpochMilli(4_000), "ADMIN", 3L, "DELETE", "inquiry", 101L, "FAILED"));

        ArgumentCaptor<FilterLogEventsRequest> requests = ArgumentCaptor.forClass(FilterLogEventsRequest.class);
        verify(client, times(2)).filterLogEvents(requests.capture());
        FilterLogEventsRequest first = requests.getAllValues().get(0);
        assertThat(first.logGroupName()).isEqualTo(GROUP);
        assertThat(first.startTime()).isEqualTo(FROM.toEpochMilli());
        // [from, to) - CloudWatch의 끝 시각은 포함이라 1ms 뺌
        assertThat(first.endTime()).isEqualTo(TO.toEpochMilli() - 1);
        assertThat(first.filterPattern())
                .isEqualTo("{ $.logger_name = \"ACCESS\" && $.message = \"*target=inquiry:101 *\" }");
        assertThat(first.nextToken()).isNull();
        assertThat(requests.getAllValues().get(1).nextToken()).isEqualTo("next-1");
    }

    @Test
    @DisplayName("거르는 조건 - 대상이 없으면 ACCESS 줄 전부, 종류만 있으면 그 종류 전부")
    void filterPatterns() {
        assertThat(CloudWatchAccessLogReader.filterPattern(new AccessLogReader.Query(null, null, FROM, TO)))
                .isEqualTo("{ $.logger_name = \"ACCESS\" }");
        assertThat(CloudWatchAccessLogReader.filterPattern(new AccessLogReader.Query("Reservation", null, FROM, TO)))
                .isEqualTo("{ $.logger_name = \"ACCESS\" && $.message = \"*target=reservation:*\" }");
    }

    @Test
    @DisplayName("기록이 너무 많으면 한도까지만 읽고 truncated로 알림")
    void truncatesAtLimit() {
        List<FilteredLogEvent> many = new ArrayList<>();
        for (int i = 0; i < CloudWatchAccessLogReader.MAX_EVENTS + 5; i++) {
            many.add(event(i, json("actor=OWNER:7 action=VIEW target=inquiry:" + i + " result=OK")));
        }
        when(client.filterLogEvents(any(FilterLogEventsRequest.class)))
                .thenReturn(page("more", many.toArray(FilteredLogEvent[]::new)));

        AccessLogReader.Result result = reader.read(new AccessLogReader.Query(null, null, FROM, TO));

        assertThat(result.truncated()).isTrue();
        assertThat(result.entries()).hasSize(CloudWatchAccessLogReader.MAX_EVENTS);
        verify(client, times(1)).filterLogEvents(any(FilterLogEventsRequest.class));
    }

    @Test
    @DisplayName("CloudWatch 호출 실패는 0건이 아니라 AccessLogQueryException")
    void failureIsNotEmpty() {
        when(client.filterLogEvents(any(FilterLogEventsRequest.class)))
                .thenThrow(CloudWatchLogsException.builder().message("AccessDenied").build());

        assertThatThrownBy(() -> reader.read(new AccessLogReader.Query(null, null, FROM, TO)))
                .isInstanceOf(AccessLogQueryException.class);
    }

    @Test
    @DisplayName("로그 그룹 이름이 비어 있으면 만들지 않음")
    void requiresLogGroup() {
        assertThatThrownBy(() -> new CloudWatchAccessLogReader(client, " ", JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static FilterLogEventsResponse page(String nextToken, FilteredLogEvent... events) {
        return FilterLogEventsResponse.builder().events(events).nextToken(nextToken).build();
    }

    private static FilteredLogEvent event(long timestamp, String message) {
        return FilteredLogEvent.builder().timestamp(timestamp).message(message).build();
    }

    // 서버 로그 한 줄 (logstash 형식의 일부)
    private static String json(String message) {
        return "{\"@timestamp\":\"2026-11-01T09:00:00+09:00\",\"level\":\"INFO\",\"logger_name\":\"ACCESS\","
                + "\"message\":\"" + message + "\",\"actor\":\"OWNER:7\"}";
    }
}
