package kr.dku.councilflow.rule;

/**
 * 규정 문서 상태. DRAFT → CONFIRMED → RETIRED.
 * 확정(CONFIRMED)한 문서만 지출 검토의 근거가 된다. 확정한 문서는 고치지 않고, 바뀌면 새 문서를 올린다.
 */
public enum RuleDocStatus {
    DRAFT, CONFIRMED, RETIRED
}
