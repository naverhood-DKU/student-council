package kr.dku.councilflow.org;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgService {

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;

    OrgService(OrgAccessGuard accessGuard, OrganizationRepository organizations, DepartmentRepository departments,
               MembershipRepository memberships) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.departments = departments;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public OrgDetail getDetail(CurrentUser user, long orgId) {
        OrgAccess access = accessGuard.requireMember(user, orgId);
        Organization org = organizations.findById(orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        List<OrgDetail.DepartmentView> departmentViews = departments.findByOrganizationIdAndActiveTrueOrderById(orgId).stream()
                .map(d -> new OrgDetail.DepartmentView(d.getId(), d.getName()))
                .toList();
        Long parentId = org.getParent() == null ? null : org.getParent().getId();
        String myPositionName = memberships.findActiveByUserIdAndOrgId(access.userId(), orgId).stream()
                .map(Membership::getPosition)
                .filter(Objects::nonNull)
                .map(Position::getName)
                .findFirst()
                .orElse(null);
        return new OrgDetail(org.getId(), org.getName(), org.getType(), parentId, org.getTerm(), org.getJoinCode(),
                access.role(), myPositionName, access.departmentIds(), access.adminView(), departmentViews);
    }

    public record GroupOption(long id, String name, OrgType type) {
    }

    /** 업무를 요청할 수 있는 그룹 목록: 자기 그룹을 뺀 모든 활성 그룹. 이 그룹 소속(또는 관리자)만 본다. */
    @Transactional(readOnly = true)
    public List<GroupOption> requestTargets(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return organizations.findByActiveTrueOrderById().stream()
                .filter(o -> o.getId() != orgId)
                .map(o -> new GroupOption(o.getId(), o.getName(), o.getType()))
                .toList();
    }

    public record OrgDetail(
            long id,
            String name,
            OrgType type,
            Long parentId,
            String term,
            /** 가입 코드. 부장·부원이 회원가입할 때 넣는다(없으면 null) */
            String joinCode,
            /** 내 역할. 서비스 관리자 열람이면 null */
            MembershipRole myRole,
            /** 내 직책 이름. 회장·서비스 관리자 열람이면 null */
            String myPositionName,
            Set<Long> myDepartmentIds,
            /** 서비스 관리자의 읽기 전용 열람인지 */
            boolean adminView,
            List<DepartmentView> departments) {

        public record DepartmentView(long id, String name) {
        }
    }
}
