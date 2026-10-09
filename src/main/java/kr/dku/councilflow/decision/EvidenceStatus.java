package kr.dku.councilflow.decision;

/** 근거 발언 검증 결과. 검증에 실패해도 후보를 버리지 않고 NEEDS_CHECK 로 표시한다(원칙 3). */
public enum EvidenceStatus {
    OK,
    /** 근거 확인 필요: 근거 발언 ID가 없거나 회의록에 없는 ID를 인용함 */
    NEEDS_CHECK
}
