package kr.dku.councilflow.decision;

/** AI가 회의록에서 읽은 결정의 성격. 참고용이며 확정은 사람이 승인해야 한다. */
public enum AiDecisionStatus {
    /** 회의에서 명확히 합의함 */
    CONFIRMED,
    /** 보류하기로 함 */
    ON_HOLD,
    /** 논의만 함("~하면 좋겠다", "다음에 다시 얘기하자") */
    DISCUSSING
}
