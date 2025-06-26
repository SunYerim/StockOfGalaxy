package com.sog.stock.application.service.kis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sog.stock.application.service.RedisService;
import com.sog.stock.domain.dto.kis.KisTokenResponseDTO;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
@Slf4j
@RequiredArgsConstructor
public class KisTokenService {

    private static final String KIS_TOKEN_ENDPOINT = "/oauth2/tokenP";
    private static final String GRANT_TYPE_VALUE = "client_credentials";
    private static final String REQUEST_BODY_GRANT_TYPE_KEY = "grant_type";
    private static final String REQUEST_BODY_APPKEY_KEY = "appkey";
    private static final String REQUEST_BODY_APPSECRET_KEY = "appsecret";
    private static final String REDIS_TOKEN_KEY_NAME = "kis_token";
    private static final String ERROR_MSG_ACCESS_TOKEN_NULL = "AccessToken is null";
    private static final String ERROR_MSG_4XX_CLIENT = "4xx Client Error"; // 4xx 에러 메시지도 상수화
    private static final String ERROR_MSG_5XX_SERVER = "5xx Server Error"; // 5xx 에러 메시지도 상수화

    private final WebClient kisApiWebClient;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    @Value("${kis.realtime-stock.appkey}")
    private String appKey;

    @Value("${kis.realtime-stock.appsecret}")
    private String appSecret;

    // get access token from redis
    // Mono -> String (blocking)
    public String getAccessToken() {
        log.info("kis토큰 접근 메서드 호출");
        String token = redisService.getValue(REDIS_TOKEN_KEY_NAME);
        if (token != null && !token.isEmpty()) {
            log.info("Redis에 토큰이 존재합니다 : {}", token);
            return token;
        }
        log.info("Redis에 토큰이 없거나 유효하지 않습니다. 새로운 토큰 발급 요청을 시도합니다.");
        return requestNewToken();
    }

    // Mono -> String (blocking)
    private String requestNewToken() {
        log.info("Preparing token request with appkey: {}", appKey);
        // 요청에 필요한 데이터
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put(REQUEST_BODY_GRANT_TYPE_KEY, GRANT_TYPE_VALUE);
        requestBody.put(REQUEST_BODY_APPKEY_KEY, appKey);
        requestBody.put(REQUEST_BODY_APPSECRET_KEY, appSecret);

        String responseBodyString;
        try {
            String requestBodyJson = objectMapper.writeValueAsString(
                requestBody); // 요청 바디를 JSON 문자열로 변환

            responseBodyString = kisApiWebClient.post()
                .uri(KIS_TOKEN_ENDPOINT)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBodyJson) // JSON 문자열을 바디로 전달
                .retrieve() // 응답 상태 코드 검증 및 응답 본문 추출 시작
                .bodyToMono(String.class) // 응답 본문을 String Mono로 받음
                .block();

        } catch (WebClientResponseException e) {
            String errorResponse = e.getResponseBodyAsString();
            if (e.getStatusCode().is4xxClientError()) {
                log.error("{}: HTTP Status {} - {}", ERROR_MSG_4XX_CLIENT, e.getStatusCode(),
                    errorResponse, e);
                throw new RuntimeException(ERROR_MSG_4XX_CLIENT + ": " + errorResponse, e);
            } else if (e.getStatusCode().is5xxServerError()) {
                log.error("{}: HTTP Status {} - {}", ERROR_MSG_5XX_SERVER, e.getStatusCode(),
                    errorResponse, e);
                throw new RuntimeException(ERROR_MSG_5XX_SERVER + ": " + errorResponse, e);
            } else {
                log.error("KIS 토큰 요청 중 WebClient 오류 발생 (HTTP {}): {}", e.getStatusCode(),
                    e.getMessage(), e);
                throw new RuntimeException("KIS token request failed: " + e.getMessage(), e);
            }
        } catch (JsonProcessingException e) { // 요청 바디 JSON 변환 실패 시
            log.error("Error converting request body to JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to prepare token request body", e);
        } catch (Exception e) {
            log.error("KIS 토큰 요청 중 예상치 못한 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to request new KIS token due to unexpected error",
                e);
        }

        KisTokenResponseDTO responseDto;
        try {
            responseDto = objectMapper.readValue(responseBodyString, KisTokenResponseDTO.class);

            if (responseDto.getAccessToken() == null || responseDto.getAccessToken().isEmpty()) {
                log.error("{}: Response: {}", ERROR_MSG_ACCESS_TOKEN_NULL, responseBodyString);
                throw new NullPointerException(ERROR_MSG_ACCESS_TOKEN_NULL);
            }

            // Redis에 토큰 저장 (블로킹 setValues 그대로 사용)
            redisService.setValues(REDIS_TOKEN_KEY_NAME, responseDto.getAccessToken(),
                Duration.ofSeconds(responseDto.getExpiresIn()));

            return responseDto.getAccessToken();
        } catch (JsonProcessingException e) { // 응답 JSON 파싱 실패 시
            log.error("Error mapping KIS token response to DTO: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to map KIS token response to DTO", e);
        } catch (Exception e) {
            log.error("KIS 토큰 응답 처리 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process KIS token response", e);
        }

//        return kisApiWebClient.post()
//            .uri(KIS_TOKEN_ENDPOINT)
//            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//            .bodyValue(requestBody)
//            .retrieve()
//            .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
//                return clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
//                    System.out.println("4xx Error: " + errorBody);
//                    return Mono.error(new RuntimeException(ERROR_MSG_4XX_CLIENT));
//                });
//            })
//            .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
//                return clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
//                    System.out.println("5xx Error: " + errorBody);
//                    return Mono.error(new RuntimeException(ERROR_MSG_5XX_SERVER));
//                });
//            })
//            .bodyToMono(KisTokenResponseDTO.class)
//            .flatMap(response -> {
//                if (response.getAccessToken() == null) {
//                    return Mono.error(new NullPointerException(ERROR_MSG_ACCESS_TOKEN_NULL));
//                }
//                redisService.setValues(REDIS_TOKEN_KEY_NAME, response.getAccessToken(),
//                    Duration.ofSeconds(response.getExpiresIn()));
//                return Mono.just(response.getAccessToken());
//            });

    }
}
