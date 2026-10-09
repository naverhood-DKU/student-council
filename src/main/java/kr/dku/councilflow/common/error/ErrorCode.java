package kr.dku.councilflow.common.error;

import org.springframework.http.HttpStatus;

/** API 오류 코드. 응답 본문 { code, message, requestId } 의 code 값이 된다. */
public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    IF_MATCH_REQUIRED(HttpStatus.BAD_REQUEST, "If-Match 헤더에 화면에서 본 version 값을 넣어 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일이 너무 큽니다. 10MB 이하로 올려 주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사람이 먼저 수정했습니다. 새로 불러온 뒤 다시 시도해 주세요."),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 변경입니다."),
    DUPLICATE(HttpStatus.CONFLICT, "이미 있는 항목입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 오류가 났습니다. 잠시 뒤 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
