package kr.dku.councilflow.expense;

import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.user.MembershipRole;

/**
 * 지출 검토에서 누가 무엇을 할 수 있는지 한 곳에 모은다.
 * 재정부 부장급이 어디까지 할지는 아직 정해지지 않았다(CLAUDE.md "단계 4에서 정한다"). 정해지면 여기만 고친다.
 * 서비스 관리자(adminView)는 어떤 경우에도 읽기 전용이다. 부원(VIEWER)은 보기만 한다.
 */
final class ExpenseReviewPolicy {

    private ExpenseReviewPolicy() {
    }

    /** 지출 검토 요청, AI 안내 다시 받기, 체크리스트 체크: 회장과 부장급 */
    static boolean canRequestOrWork(OrgAccess access) {
        return !access.adminView() && access.role() != null && access.role() != MembershipRole.VIEWER;
    }

    /** 검토 완료 표시: 지금은 회장만 */
    static boolean canComplete(OrgAccess access) {
        return !access.adminView() && access.isOwner();
    }
}
