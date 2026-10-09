package kr.dku.councilflow.auth;

import java.util.Arrays;
import java.util.Set;
import kr.dku.councilflow.user.MembershipRole;

/**
 * OrgAccessGuard 가 확인해 준 결과: 이 사용자는 이 조직에서 이런 역할들을 갖고, 이 부서들에 속해 있다.
 */
public record OrgAccess(long userId, long orgId, Set<MembershipRole> roles, Set<Long> departmentIds) {

    public boolean hasAnyRole(MembershipRole... wanted) {
        return Arrays.stream(wanted).anyMatch(roles::contains);
    }

    public boolean belongsToDepartment(Long departmentId) {
        return departmentId != null && departmentIds.contains(departmentId);
    }
}
