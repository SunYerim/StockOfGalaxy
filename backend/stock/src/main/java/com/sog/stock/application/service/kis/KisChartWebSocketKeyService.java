package com.sog.stock.application.service.kis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sog.stock.application.service.RedisService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@Slf4j
@EnableScheduling
@RequiredArgsConstructor
public class KisChartWebSocketKeyService {

    private static final String KIS_APPROVAL_ENDPOINT = "/oauth2/Approval";
    private static final String KIS_CONTENT_TYPE_HEADER = "application/json; charset=utf-8";

    private static final String GRANT_TYPE_VALUE = "client_credentials";
    private static final String REQUEST_BODY_GRANT_TYPE_KEY = "grant_type";
    private static final String REQUEST_BODY_APPKEY_KEY = "appkey";
    private static final String REQUEST_BODY_SECRETKEY_KEY = "secretkey";

    private static final String RESPONSE_APPROVAL_KEY = "approval_key";

    private static final String REDIS_CHART_KEY_NAME = "kisChartKey";
    private static final int REDIS_KEY_EXPIRATION_HOURS = 24;

    private static final String CRON_EXPRESSION_MIDNIGHT = "0 0 0 * * *";

    private final WebClient kisApiWebClient;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    @Value("${kis.chart.appkey}")
    private String appKey;

    @Value("${kis.chart.appsecret}")
    private String appSecret;


    // Redis에서 키를 조회하여 반환
    public String getRealTimeWebSocketKey() {
        String key = redisService.getValue(REDIS_CHART_KEY_NAME);

        if (key == null) {
            log.info("redis에 웹소켓 키가 없습니다. 발급요청을 시도합니다.");
            requestNewWebSocketKey();
            key = redisService.getValue(REDIS_CHART_KEY_NAME);
            log.info("chart - 새로운 웹소켓 키가 발급되었습니다: {}", key);
        } else {
            log.info("이미 chart 키가 존재합니다: {}", key);
        }
        return key;
    }

    // 매일 자정에 새로 WebSocket 키 요청
    @Scheduled(cron = CRON_EXPRESSION_MIDNIGHT)
    public void requestWebSocketKeyScheduled() {
        log.info("자정 12시 입니다! - chart");
        requestNewWebSocketKey();
    }

    public void requestNewWebSocketKey() {
        log.info("Requesting new WebSocket key from KIS...");
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put(REQUEST_BODY_GRANT_TYPE_KEY, GRANT_TYPE_VALUE);
        requestBody.put(REQUEST_BODY_APPKEY_KEY, appKey);
        requestBody.put(REQUEST_BODY_SECRETKEY_KEY, appSecret);

        try {
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            String response = kisApiWebClient.post()
                .uri(KIS_APPROVAL_ENDPOINT)
                .header(HttpHeaders.CONTENT_TYPE, KIS_CONTENT_TYPE_HEADER)
                .bodyValue(requestBodyJson)
                .retrieve()
                .bodyToMono(String.class)
                .block(); // 동기적 실행

            String approvalKey = extractApprovalKeyFromResponse(response);
            if (approvalKey != null) {
                redisService.setValues(REDIS_CHART_KEY_NAME, approvalKey,
                    Duration.ofHours(REDIS_KEY_EXPIRATION_HOURS));
                log.info("New Chart WebSocket approval_key successfully saved to Redis: {}",
                    approvalKey);
            } else {
                log.error("Failed to extract approval_key from KIS response(chart): {}", response);
            }
        } catch (JsonProcessingException e) {
            log.error("Error converting request body to JSON: {}", e.getMessage());
        }
    }

    // 응답에서 approval_key 추출
    private String extractApprovalKeyFromResponse(String response) {
        try {
            // JSON 응답 파싱
            JSONObject jsonResponse = new JSONObject(response);
            return jsonResponse.getString(RESPONSE_APPROVAL_KEY);  // 응답에서 approval_key 추출
        } catch (JSONException e) {
            log.error("Error parsing approval_key from response: {}", e.getMessage());
            return null;
        }
    }
}
