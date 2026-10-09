package kr.dku.councilflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정. secret 은 환경변수 JWT_SECRET 으로만 받는다.
 * HS256 서명이므로 32바이트 이상이어야 하고, 값이 없으면 앱이 뜨지 않는다.
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, String issuer, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.contains("${") || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 환경변수에 32바이트 이상의 값을 넣어야 합니다.");
        }
    }
}
