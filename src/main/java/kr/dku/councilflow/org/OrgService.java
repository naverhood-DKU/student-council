package kr.dku.councilflow.org;

import java.util.List;
import java.util.Set;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgService {

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;

    OrgService(OrgAccessGuard accessGuard, OrganizationRepository organizations, DepartmentRepository departments) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public OrgDetail getDetail(CurrentUser user, long orgId) {
        OrgAccess access = accessGuard.requireMember(user, orgId);
        Organization org = organizations.findById(orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        List<OrgDetail.DepartmentView> departmentViews = departments.findByOrganizationIdOrderById(orgId).stream()
                .map(d -> new OrgDetail.DepartmentView(d.getId(), d.getName()))
                .toList();
        Long parentId = org.getParent() == null ? null : org.getParent().getId();
        return new OrgDetail(org.getId(), org.getName(), org.getType(), parentId, org.getTerm(),
                access.roles(), access.departmentIds(), departmentViews);
    }

    public record ChildOrg(long id, String name, OrgType type) {
    }

    /** 직속 하위 조직 목록(전달할 대상 고르기). 이 조직 소속만 볼 수 있다. */
    @Transactional(readOnly = true)
    public List<ChildOrg> children(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return organizations.findByParentIdAndActiveTrueOrderById(orgId).stream()
                .map(o -> new ChildOrg(o.getId(), o.getName(), o.getType()))
                .toList();
    }

    public record OrgDetail(
            long id,
            String name,
            OrgType type,
            Long parentId,
            String term,
            Set<MembershipRole> myRoles,
            Set<Long> myDepartmentIds,
            List<DepartmentView> departments) {

        public record DepartmentView(long id, String name) {
        }
    }
}
