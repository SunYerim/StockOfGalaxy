package com.sog.stock.application.service.kis;

import com.sog.stock.application.service.RedisService;
import com.sog.stock.domain.dto.kis.KisTokenResponseDTO;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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

    @Value("${kis.realtime-stock.appkey}")
    private String appKey;

    @Value("${kis.realtime-stock.appsecret}")
    private String appSecret;

    // get access token from redis
    public Mono<String> getAccessToken() {
        log.info("kis토큰 접근 메서드 호출");
        String token = redisService.getValue(REDIS_TOKEN_KEY_NAME);
        if (token != null) {
            return Mono.just(token);
        }
        return requestNewToken();
    }

    private Mono<String> requestNewToken() {
        log.info("Preparing token request with appkey: {}", appKey);
        // 요청에 필요한 데이터
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put(REQUEST_BODY_GRANT_TYPE_KEY, GRANT_TYPE_VALUE);
        requestBody.put(REQUEST_BODY_APPKEY_KEY, appKey);
        requestBody.put(REQUEST_BODY_APPSECRET_KEY, appSecret);

        return kisApiWebClient.post()
            .uri(KIS_TOKEN_ENDPOINT)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                return clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
                    System.out.println("4xx Error: " + errorBody);
                    return Mono.error(new RuntimeException(ERROR_MSG_4XX_CLIENT));
                });
            })
            .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                return clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
                    System.out.println("5xx Error: " + errorBody);
                    return Mono.error(new RuntimeException(ERROR_MSG_5XX_SERVER));
                });
            })
            .bodyToMono(KisTokenResponseDTO.class)
            .flatMap(response -> {
                if (response.getAccessToken() == null) {
                    return Mono.error(new NullPointerException(ERROR_MSG_ACCESS_TOKEN_NULL));
                }
                redisService.setValues(REDIS_TOKEN_KEY_NAME, response.getAccessToken(),
                    Duration.ofSeconds(response.getExpiresIn()));
                return Mono.just(response.getAccessToken());
            });

    }
}
