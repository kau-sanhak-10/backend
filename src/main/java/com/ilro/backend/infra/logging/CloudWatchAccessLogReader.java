package com.ilro.backend.infra.logging;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 서버(dev·prod)의 접근 기록 읽기 - 앱 로그가 쌓이는 CloudWatch 로그 그룹(/ilro/<환경>/app)에서 ACCESS 줄만 찾음
// 로그는 한 줄이 JSON 하나(logstash 형식)라 logger_name·message로 거름
// 필요한 것 (배포 쪽): 서버 역할에 logs:FilterLogEvents 권한, 로그 그룹 보관 기간 365일 (IF-06)
@Component
@Profile({"dev", "prod"})
public class CloudWatchAccessLogReader implements AccessLogReader, DisposableBean {

    // 한 번 조회에 읽는 최대 줄 수 - 넘으면 truncated로 알림
    static final int MAX_EVENTS = 5_000;
    private static final int PAGE_LIMIT = 1_000;

    private final CloudWatchLogsClient client;
    private final String logGroup;
    private final JsonMapper jsonMapper;

    @Autowired
    public CloudWatchAccessLogReader(@Value("${app.access-log.log-group}") String logGroup,
                                     @Value("${app.access-log.region}") String region, JsonMapper jsonMapper) {
        this(CloudWatchLogsClient.builder().region(Region.of(region)).build(), logGroup, jsonMapper);
    }

    // 테스트는 가짜 클라이언트를 넣음
    CloudWatchAccessLogReader(CloudWatchLogsClient client, String logGroup, JsonMapper jsonMapper) {
        // 로그 그룹 이름이 없으면 서버가 뜨지 않게 - 배포 직후 health 확인에서 바로 드러남
        if (logGroup == null || logGroup.isBlank()) {
            throw new IllegalStateException("app.access-log.log-group이 비어 있음");
        }
        this.client = client;
        this.logGroup = logGroup;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Result read(Query query) {
        List<AccessLogEntry> entries = new ArrayList<>();
        boolean truncated = false;
        try {
            String nextToken = null;
            do {
                FilterLogEventsResponse response = client.filterLogEvents(FilterLogEventsRequest.builder()
                        .logGroupName(logGroup)
                        .startTime(query.from().toEpochMilli())
                        // CloudWatch의 endTime은 그 시각까지 포함 - [from, to)에 맞춤
                        .endTime(query.to().toEpochMilli() - 1)
                        .filterPattern(filterPattern(query))
                        .limit(PAGE_LIMIT)
                        .nextToken(nextToken)
                        .build());
                for (FilteredLogEvent event : response.events()) {
                    if (entries.size() >= MAX_EVENTS) {
                        truncated = true;
                        break;
                    }
                    parse(event).filter(entry -> matches(query, entry)).ifPresent(entries::add);
                }
                nextToken = response.nextToken();
                if (nextToken != null && entries.size() >= MAX_EVENTS) {
                    truncated = true;
                }
            } while (nextToken != null && !truncated);
        } catch (RuntimeException e) {
            throw new AccessLogQueryException("CloudWatch 접근 기록 조회 실패", e);
        }
        return new Result(entries, truncated);
    }

    // JSON 로그 줄에서 ACCESS 로거의 줄만, 대상이 정해졌으면 메시지의 target= 부분으로 한 번 더
    // (대상 종류의 대소문자는 CloudWatch가 가리므로 여기서는 종류를 소문자로 맞추고, 읽은 뒤 matches로 다시 확인)
    static String filterPattern(Query query) {
        StringBuilder pattern = new StringBuilder("{ $.logger_name = \"ACCESS\"");
        if (query.targetType() != null) {
            pattern.append(" && $.message = \"*target=").append(query.targetType().toLowerCase(Locale.ROOT)).append(':')
                    .append(query.targetId() == null ? "*" : query.targetId() + " *").append('"');
        }
        return pattern.append(" }").toString();
    }

    private java.util.Optional<AccessLogEntry> parse(FilteredLogEvent event) {
        Instant at = Instant.ofEpochMilli(event.timestamp());
        String message = event.message();
        try {
            JsonNode line = jsonMapper.readTree(message);
            if (line.isObject() && line.path("message").isString()) {
                message = line.path("message").asString();
            }
        } catch (JacksonException e) {
            // JSON이 아닌 줄(옛 형식)은 줄 그대로 읽어 봄
        }
        return AccessLogEntry.parse(at, message);
    }

    private static boolean matches(Query query, AccessLogEntry entry) {
        return (query.targetType() == null || query.targetType().equalsIgnoreCase(entry.targetType()))
                && (query.targetId() == null || query.targetId().equals(entry.targetId()));
    }

    @Override
    public void destroy() {
        client.close();
    }
}
