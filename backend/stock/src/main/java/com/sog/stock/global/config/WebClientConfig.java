package com.sog.stock.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${kis.base-api-url}")
    private String kisBaseApiUrl;

    /**
     * KIS API 통신을 위한 WebClient 빈을 설정합니다. WebClient.Builder를 주입받아 커스텀 설정을 적용합니다.
     *
     * @param webClientBuilder Spring이 제공하는 WebClient.Builder
     * @return KIS API 전용으로 설정된 WebClient 인스턴스
     */

    @Bean
    public WebClient kisApiWebClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
            .baseUrl(kisBaseApiUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE,
                MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

}
