package kr.dku.councilflow.expense;

/**
 * 지출 검토 상태. "문제없음" 상태는 일부러 없다(CLAUDE.md 원칙 5).
 *
 * CANDIDATES_FOUND  확정된 규정에서 후보 조항을 찾았다. 아직 절차 안내(AI) 전이다.
 * NEEDS_CHECK       후보 조항을 못 찾았거나 확정된 규정이 없다. 사람이 직접 확인해야 한다.
 * PROCEDURE_FOUND / POSSIBLE_CONFLICT / REVIEWED  는 AI 절차 안내와 사람의 검토를 붙이는 다음 작업에서 쓴다.
 */
public enum ExpenseReviewStatus {
    CANDIDATES_FOUND, NEEDS_CHECK, PROCEDURE_FOUND, POSSIBLE_CONFLICT, REVIEWED
}
