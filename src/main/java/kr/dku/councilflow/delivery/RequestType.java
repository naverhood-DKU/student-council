package kr.dku.councilflow.delivery;

public enum RequestType {
    /** 알림: 수신 조직이 수락(확인)하면 끝 */
    INFORM,
    /** 협조 요청: 수락 → 진행 → 결과 제출 → 발신 조직 완료 확정 */
    COOPERATE,
    /** 결과 보고 요청: 협조 요청과 같은 흐름 */
    RESULT_NOTICE
}
