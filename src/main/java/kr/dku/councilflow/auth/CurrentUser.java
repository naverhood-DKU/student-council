package kr.dku.councilflow.auth;

/** 로그인한 사용자. 컨트롤러 메서드 인자로 받으면 토큰에서 꺼내 준다(CurrentUserArgumentResolver). */
public record CurrentUser(long id) {
}
