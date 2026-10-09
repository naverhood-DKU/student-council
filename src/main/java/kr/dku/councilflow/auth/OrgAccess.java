package kr.dku.councilflow.auth;

import java.util.Set;
import kr.dku.councilflow.user.MembershipRole;

/**
 * OrgAccessGuard 가 확인해 준 결과: 이 사용자가 이 그룹에서 어떤 역할인지, 어느 부서인지.
 * adminView 면 그룹 소속이 아닌 서비스 관리자의 읽기 전용 열람이다(role 은 null, 어떤 변경도 못 한다).
 */
public record OrgAccess(long userId, long orgId, MembershipRole role, Set<Long> departmentIds, boolean adminView) {

    public boolean isOwner() {
        return role == MembershipRole.OWNER;
    }

    /** 이 부서의 일을 직접 처리(시작·결과 제출)할 수 있나: owner, 또는 그 부서의 editor */
    public boolean canWorkFor(Long departmentId) {
        return isOwner()
                || (role == MembershipRole.EDITOR && departmentId != null && departmentIds.contains(departmentId));
    }
}
