package kr.dku.councilflow.common.web;

import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;

/**
 * If-Match 헤더로 받은 version 확인.
 * 화면이 본 버전과 지금 DB 버전이 다르면 다른 사람이 먼저 고친 것이므로 409로 돌려보낸다.
 * 형식: If-Match: 3  /  "3"  /  W/"3"
 */
public final class IfMatch {

    public static final String HEADER = "If-Match";

    private IfMatch() {
    }

    public static void check(String header, Long currentVersion) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ErrorCode.IF_MATCH_REQUIRED);
        }
        String value = header.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        long expected;
        try {
            expected = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.IF_MATCH_REQUIRED);
        }
        if (currentVersion == null || expected != currentVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
    }
}
