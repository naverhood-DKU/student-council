package kr.dku.councilflow.auth;

/** expiresIn 은 초 단위. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
