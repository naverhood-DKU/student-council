package kr.dku.councilflow.ai;

/** CLOVA Speech 호출 실패. code 는 화면과 로그에 남는 오류 종류다(녹음 내용·키를 넣지 않는다). */
public class SpeechException extends RuntimeException {

    public enum Kind {
        /** 일시 오류(연결 실패, 429, 5xx). 다시 시도할 수 있다. */
        RETRYABLE,
        /** 다시 해도 같은 결과(키 없음, 파일 없음, 4xx, 응답 형식 오류). */
        PERMANENT,
        /** 결과를 기다리다 시간이 넘어 처리했는지 알 수 없음. 자동으로 다시 부르지 않는다. */
        UNKNOWN_OUTCOME
    }

    private final Kind kind;
    private final String code;

    public SpeechException(Kind kind, String code) {
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
