package kr.dku.councilflow.auth;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조직 단위 인가. 조직 데이터를 다루는 모든 API는 먼저 이 컴포넌트를 거친다.
 *
 * - 토큰이 아니라 DB의 활성 Membership 을 요청마다 확인한다(소속을 끊으면 즉시 반영).
 * - 그 조직에 직접 소속된 경우만 허용한다. 상위 조직 소속이라고 하위 조직을 볼 수 없다(원칙 8).
 * - 조직이 없을 때도 403으로 답해서 다른 조직의 존재 여부를 알려 주지 않는다.
 */
@Component
public class OrgAccessGuard {

    private final MembershipRepository memberships;

    OrgAccessGuard(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public OrgAccess requireMember(CurrentUser user, long orgId) {
        return find(user, orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    /** 소속이면 역할·부서를, 아니면 빈 값을 돌려준다(보낸 쪽인지 받은 쪽인지 가릴 때). */
    @Transactional(readOnly = true)
    public Optional<OrgAccess> find(CurrentUser user, long orgId) {
        List<Membership> active = memberships.findActiveByUserIdAndOrgId(user.id(), orgId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Set<MembershipRole> roles = active.stream()
                .map(Membership::getRole)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(MembershipRole.class)));
        Set<Long> departmentIds = active.stream()
                .map(Membership::getDepartment)
                .filter(Objects::nonNull)
                .map(department -> department.getId())
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new OrgAccess(user.id(), orgId, roles, departmentIds));
    }

    /** 그 조직에서 주어진 역할 중 하나라도 있어야 통과. */
    @Transactional(readOnly = true)
    public OrgAccess requireRole(CurrentUser user, long orgId, MembershipRole... roles) {
        OrgAccess access = requireMember(user, orgId);
        if (!access.hasAnyRole(roles)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return access;
    }
}
