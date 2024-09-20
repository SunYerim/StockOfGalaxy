package com.sog.user.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sog.user.application.service.user.MemberDetailService;
import com.sog.user.application.service.user.RedisService;
import com.sog.user.infrastructure.security.JwtAuthenticationFilter;
import com.sog.user.infrastructure.security.JwtCookieUtil;
import com.sog.user.infrastructure.security.JwtTokenProvider;
import com.sog.user.infrastructure.security.LoginFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
public class SecurityConfig {

    private final AuthenticationConfiguration authenticationConfiguration;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final MemberDetailService memberDetailService;
    private final JwtCookieUtil jwtCookieUtil;

    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        LoginFilter loginFilter = new LoginFilter(authenticationManager(), jwtTokenProvider,
            objectMapper(), redisService, memberDetailService, jwtCookieUtil);
        loginFilter.setFilterProcessesUrl("/api/user/public/login"); // 로그인 엔드포인트

        httpSecurity
            .cors((corsCustomizer -> corsCustomizer.configurationSource(
                new CorsConfigurationSource() {

                    @Override
                    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {

                        CorsConfiguration configuration = new CorsConfiguration();

                        configuration.setAllowedOrigins(
                            Arrays.asList("http://localhost:3000",
                                "https://ssafy11s.com", "https://www.ssafy11s.com",
                                "http://www.ssafy11s.com"));
                        configuration.setAllowedMethods(Collections.singletonList("*"));
                        configuration.setAllowCredentials(true);
                        configuration.setAllowedHeaders(Collections.singletonList("*"));
                        configuration.setExposedHeaders(Collections.singletonList("Authorization"));
                        configuration.setMaxAge(3600L);

                        return configuration;
                    }
                })));
        httpSecurity
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(user -> user
                .requestMatchers("/api/user/public/login", "/api/user/public/join",
                    "/api/user/public/validate/**", "/api/user/public/request-verification-code",
                    "/api/user/public/request-verification", "/api/user/public/change-password",
                    "/api/user/public/reissue", "/api/user/public/logout")
                .permitAll()

                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter.class)
            .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class) // 로그인 필터 추가
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return httpSecurity.build();
    }
}