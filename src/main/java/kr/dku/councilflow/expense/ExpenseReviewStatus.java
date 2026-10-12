package kr.dku.councilflow.expense;

/**
 * 지출 검토 상태. "문제없음" 상태는 일부러 없다(CLAUDE.md 원칙 5).
 *
 * CANDIDATES_FOUND     확정된 규정에서 후보 조항을 찾았다. 아직 절차 안내(AI) 전이다.
 * NEEDS_CHECK          후보 조항을 못 찾았거나, AI 안내에서 서버가 확인한 것이 없다. 사람이 직접 확인해야 한다.
 * PROCEDURE_FOUND      서버가 확인한 필요 승인·증빙(또는 한도)이 있다.
 * POSSIBLE_CONFLICT    후보 조항끼리 다르게 말한다. 어느 쪽이 맞는지는 정하지 않는다.
 * POSSIBLY_RESTRICTED  후보 조항 중 이 지출을 금지·제한하는 내용이 있을 수 있다. 절차가 있다는 뜻이 아니다.
 * REVIEWED             사람이 안내와 조항 원문을 확인했다(규정에 맞다는 뜻이 아니다).
 *
 * 안내가 끝난 뒤의 우선순위: POSSIBLY_RESTRICTED > POSSIBLE_CONFLICT > PROCEDURE_FOUND > NEEDS_CHECK.
 */
public enum ExpenseReviewStatus {
    CANDIDATES_FOUND, NEEDS_CHECK, PROCEDURE_FOUND, POSSIBLE_CONFLICT, POSSIBLY_RESTRICTED, REVIEWED
}
