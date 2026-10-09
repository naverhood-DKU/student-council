package kr.dku.councilflow.job;

/**
 * 작업 처리 실패. code 는 화면과 로그에 남는 오류 종류다(원문·키를 넣지 않는다).
 */
public class JobFailure extends RuntimeException {

    public enum Kind {
        /** 일시 오류(429, 5xx, 연결 실패). 시도 횟수가 남아 있으면 나중에 다시 한다. */
        RETRYABLE,
        /** 다시 해도 같은 결과(잘못된 요청, 키 없음, 응답 형식 오류 2회). 바로 FAILED. */
        PERMANENT,
        /** 공급자가 처리했는지 알 수 없음(응답 대기 중 시간 초과). FAILED_UNKNOWN, 자동 재호출 금지. */
        UNKNOWN_OUTCOME
    }

    private final Kind kind;
    private final String code;

    private JobFailure(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public static JobFailure retryable(String code) {
        return new JobFailure(Kind.RETRYABLE, code);
    }

    public static JobFailure permanent(String code) {
        return new JobFailure(Kind.PERMANENT, code);
    }

    public static JobFailure unknownOutcome(String code) {
        return new JobFailure(Kind.UNKNOWN_OUTCOME, code);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
