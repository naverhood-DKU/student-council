package kr.dku.councilflow.user;

public enum MembershipRole {
    /** 조직 관리자: 계정 초대·소속 관리 */
    ORG_ADMIN,
    /** 회의 책임자(회장): 회의 결정 승인 */
    MEETING_CHAIR,
    /** 부서 구성원: 자기 부서 업무 처리 */
    DEPT_MEMBER,
    /** 재정 담당자: 규정 등록, 지출 안내 검토 */
    FINANCE_REVIEWER
}
