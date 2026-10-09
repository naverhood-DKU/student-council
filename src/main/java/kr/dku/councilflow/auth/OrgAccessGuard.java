package kr.dku.councilflow.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그룹 단위 인가. 그룹 데이터를 다루는 모든 API는 먼저 이 컴포넌트를 거친다.
 *
 * - 토큰이 아니라 DB의 활성 Membership 을 요청마다 확인한다(소속을 끊으면 즉시 반영).
 * - 그 그룹에 직접 소속된 경우만 허용한다. 다른 그룹은 요청받은 것만 본다(원칙 8).
 * - 서비스 관리자는 소속이 없어도 모든 그룹을 읽기 전용으로 볼 수 있다(requireMember). 변경은 못 한다.
 * - 그룹이 없을 때도 403으로 답해서 다른 그룹의 존재 여부를 알려 주지 않는다.
 */
@Component
public class OrgAccessGuard {

    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;

    OrgAccessGuard(MembershipRepository memberships, AppUserRepository users, OrganizationRepository organizations) {
        this.memberships = memberships;
        this.users = users;
        this.organizations = organizations;
    }

    /** 조회용: 그룹 소속(owner·editor·viewer) 또는 서비스 관리자(읽기 전용). */
    @Transactional(readOnly = true)
    public OrgAccess requireMember(CurrentUser user, long orgId) {
        Optional<OrgAccess> member = find(user, orgId);
        if (member.isPresent()) {
            return member.get();
        }
        if (isServiceAdmin(user) && organizations.existsById(orgId)) {
            return new OrgAccess(user.id(), orgId, null, Set.of(), true);
        }
        throw new ApiException(ErrorCode.FORBIDDEN);
    }

    /** 그 그룹의 owner(회장)만 통과. 서비스 관리자도 그룹 내용을 바꿀 수는 없다. */
    @Transactional(readOnly = true)
    public OrgAccess requireOwner(CurrentUser user, long orgId) {
        OrgAccess access = find(user, orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        if (!access.isOwner()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "그룹 회장(owner)만 할 수 있습니다.");
        }
        return access;
    }

    /** 그룹 소속이면 역할·부서를, 아니면 빈 값(보낸 쪽인지 받은 쪽인지 가릴 때). 관리자 열람은 포함하지 않는다. */
    @Transactional(readOnly = true)
    public Optional<OrgAccess> find(CurrentUser user, long orgId) {
        List<Membership> active = memberships.findActiveByUserIdAndOrgId(user.id(), orgId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        // 임기가 겹쳐 활성 줄이 둘 이상이면 가장 높은 역할을 쓴다.
        Membership top = active.stream().min(Comparator.comparing(Membership::getRole)).orElseThrow();
        Set<Long> departmentIds = active.stream()
                .map(Membership::getDepartment)
                .filter(Objects::nonNull)
                .map(department -> department.getId())
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new OrgAccess(user.id(), orgId, top.getRole(), departmentIds, false));
    }

    @Transactional(readOnly = true)
    public boolean isServiceAdmin(CurrentUser user) {
        return users.findById(user.id()).map(u -> u.isServiceAdmin()).orElse(false);
    }

    /** 서비스 관리자만 통과(그룹 만들기, owner 부여·회수). */
    @Transactional(readOnly = true)
    public void requireServiceAdmin(CurrentUser user) {
        if (!isServiceAdmin(user)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "서비스 관리자만 할 수 있습니다.");
        }
    }
}
