package kr.dku.councilflow.common.error;

import kr.dku.councilflow.common.web.RequestIdFilter;

/** 모든 오류 응답의 본문. */
public record ErrorBody(String code, String message, String requestId) {

    public static ErrorBody of(ErrorCode code) {
        return of(code, code.defaultMessage());
    }

    public static ErrorBody of(ErrorCode code, String message) {
        return new ErrorBody(code.name(), message, RequestIdFilter.currentRequestId());
    }
}
