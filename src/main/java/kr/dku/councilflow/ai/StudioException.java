package kr.dku.councilflow.ai;

/** CLOVA Studio 호출 실패. code 는 오류 종류(예: AI_HTTP_503)이고 원문·키를 담지 않는다. */
public class StudioException extends RuntimeException {

    public enum Kind {
        /** 요청이 공급자에게 닿지 않았거나 일시 오류(연결 실패, 429, 5xx) */
        RETRYABLE,
        /** 다시 보내도 같은 결과(4xx, 키 없음, 공급자 오류 코드) */
        PERMANENT,
        /** 요청은 보냈는데 응답을 못 받음. 공급자가 처리했는지 알 수 없다. */
        UNKNOWN_OUTCOME
    }

    private final Kind kind;
    private final String code;

    public StudioException(Kind kind, String code) {
        super(code);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
