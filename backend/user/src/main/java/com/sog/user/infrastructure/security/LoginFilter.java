package com.sog.user.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sog.user.application.service.user.MemberDetailService;
import com.sog.user.application.service.user.RedisService;
import com.sog.user.domain.dto.user.LoginDTO;
import com.sog.user.domain.model.Member;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@RequiredArgsConstructor
@Slf4j
public class LoginFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final RedisService redisService;
    private final MemberDetailService memberDetailService;
    private final JwtCookieUtil jwtCookieUtil;

    public Authentication attemptAuthentication(HttpServletRequest request,
        HttpServletResponse response) throws AuthenticationException {
        try {
            // 요청 본문에서 JSON 데이터를 읽어와 LoginDTO 객체로 변환
            LoginDTO loginRequest = objectMapper.readValue(request.getInputStream(),
                LoginDTO.class);

            // JSON에서 userId와 password를 추출
            String userId = loginRequest.getUserId();
            String password = loginRequest.getPassword();

            // UsernamePasswordAuthenticationToken 생성
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                userId, password);

            // 인증 시도
            return authenticationManager.authenticate(authenticationToken);
        } catch (IOException e) {
            log.error("Failed to parse login request", e);
            log.error("Failed to parse login request", e);
            throw new BadCredentialsException("Invalid login request format", e);
        }
    }

    // 로그인 성공했을 시 실행하는 메서드
    protected void successfulAuthentication(HttpServletRequest request,
        HttpServletResponse response, FilterChain chain, Authentication authResult)
        throws IOException, ServletException {
        UserDetails userDetails = (UserDetails) authResult.getPrincipal();

        // 사용자 정보를 추가로 가져오는 로직
        Member member = (Member) memberDetailService.loadUserByUsername(userDetails.getUsername());
        long memberId = member.getMemberId();
        log.info("success  memberId: {}", memberId);

        // 토큰을 생성하고 발급
        String accessToken = jwtTokenProvider.generateAccessToken(memberId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(memberId);
        log.info("accessToken입니다: {}", accessToken);

        response.setHeader("Authorization", "Bearer " + accessToken);
        response.addCookie(jwtCookieUtil.createCookie("refresh", refreshToken));

        // Redis에 Refresh Token 저장 (24시간 유효 시간 설정)
        Duration expiration = Duration.ofHours(24);
        redisService.setValues(String.valueOf(memberId), refreshToken, expiration);

        // 응답 본문에 memberId와 accessToken을 포함
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // CORS 설정을 위해 헤더 추가
        response.setHeader("Access-Control-Expose-Headers", "Authorization");

        // 응답 본문에 포함할 데이터 생성
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("memberId", memberId);

        // JSON 변환 및 응답 본문에 쓰기
        String jsonResponse = objectMapper.writeValueAsString(responseBody);
        response.getWriter().write(jsonResponse);
    }

    // 로그인 실패시 실행하는 메서드
    protected void unsuccessfulAuthentication(HttpServletRequest request,
        HttpServletResponse response, AuthenticationException failed)
        throws IOException, ServletException {
        response.setStatus(401);
    }

}
