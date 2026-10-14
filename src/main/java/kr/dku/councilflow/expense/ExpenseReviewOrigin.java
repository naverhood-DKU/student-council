package kr.dku.councilflow.expense;

/**
 * 지출 검토를 만든 방식.
 *
 * MANUAL  사람(회장·부장급)이 지출 절차 확인 화면에서 요청했다.
 * AUTO    회의 분석이 끝난 뒤 돈과 관련된 업무 카드에 대해 서버가 자동으로 만들었다(task_id 가 있다).
 */
public enum ExpenseReviewOrigin {
    MANUAL, AUTO
}
