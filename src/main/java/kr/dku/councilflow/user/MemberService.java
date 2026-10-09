package kr.dku.councilflow.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.org.Position;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 그룹 구성원 관리. 회장(owner)이 직책·부서를 정해 초대하고, 직책·부서를 바꾸고, 소속을 끊는다.
 * 권한은 직책의 권한 수준(부장급 editor·부원급 viewer)을 따른다. 부장급은 일할 부서가 있어야 한다.
 * 회장 부여·회수는 서비스 관리자만 한다(AdminService).
 */
@Service
public class MemberService {

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final MembershipRepository memberships;
    private final UserAccounts accounts;
    private final AuditService audit;

    MemberService(OrgAccessGuard accessGuard, OrganizationRepository organizations, DepartmentRepository departments,
                  MembershipRepository memberships, UserAccounts accounts, AuditService audit) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.departments = departments;
        this.memberships = memberships;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** 구성원 한 명. role 은 권한(직책의 권한 수준), positionName 은 화면에 보이는 직책 이름(회장은 null). */
    public record MemberView(long membershipId, long userId, String loginId, String name, MembershipRole role,
                             Long positionId, String positionName, Long departmentId, String departmentName,
                             long version) {

        static MemberView of(Membership m) {
            return new MemberView(m.getId(), m.getUser().getId(), m.getUser().getEmail(), m.getUser().getName(),
                    m.getRole(), m.getPosition() == null ? null : m.getPosition().getId(),
                    m.getPosition() == null ? null : m.getPosition().getName(),
                    m.getDepartment() == null ? null : m.getDepartment().getId(),
                    m.getDepartment() == null ? null : m.getDepartment().getName(), m.getVersion());
        }
    }

    public record AddMember(
            @NotBlank @Size(max = 255) @Pattern(regexp = "\\S+", message = "아이디에 공백을 넣을 수 없습니다.") String loginId,
            @Size(max = 50) String name,
            @Size(max = 100) String initialPassword,
            /** 직책. 없으면 role 에 맞는 첫 직책 */
            Long positionId,
            MembershipRole role,
            Long departmentId) {
    }

    /** 그룹 구성원 목록(owner → editor → viewer 순). 그룹 소속(또는 서비스 관리자)이면 볼 수 있다. */
    @Transactional(readOnly = true)
    public List<MemberView> list(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return memberships.findActiveByOrgId(orgId).stream()
                .sorted(Comparator.comparing(Membership::getRole).thenComparing(Membership::getId))
                .map(MemberView::of)
                .toList();
    }

    /** 구성원 초대: 아이디가 있으면 그 계정에 소속을 주고, 없으면 이름·초기 비밀번호로 계정을 만든다. */
    @Transactional
    public MemberView add(CurrentUser user, long orgId, AddMember request) {
        accessGuard.requireOwner(user, orgId);
        Organization org = organizations.findById(orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        Position position = accounts.position(orgId, request.positionId(), request.role());
        Department department = department(request.departmentId(), orgId);
        UserAccounts.requireDepartmentForEditor(position.getLevel(), department);

        AppUser member = accounts.findOrCreate(request.loginId(), request.name(), request.initialPassword());
        boolean alreadyActive = memberships.findActiveByUserIdAndOrgId(member.getId(), orgId).stream()
                .anyMatch(Membership::isActive);
        if (alreadyActive) {
            throw new ApiException(ErrorCode.DUPLICATE, "이미 이 그룹의 구성원입니다.");
        }
        Membership membership = accounts.grant(member, org, position, department);
        memberships.flush();
        audit.record(user.id(), orgId, "MEMBER_ADDED", "MEMBERSHIP", membership.getId(), null, snapshot(membership));
        return MemberView.of(membership);
    }

    /**
     * 직책·부서 바꾸기. { "positionId": 5, "departmentId": 3 | null } (If-Match: version)
     * positionId 대신 "role": "EDITOR" | "VIEWER" 를 보내면 그 권한 수준의 첫 직책을 준다.
     */
    @Transactional
    public MemberView update(CurrentUser user, long membershipId, String ifMatch, JsonNode body) {
        Membership membership = managedMembership(user, membershipId);
        IfMatch.check(ifMatch, membership.getVersion());
        Map<String, Object> before = snapshot(membership);
        long orgId = membership.getOrganization().getId();
        if (body != null && (body.has("positionId") || body.has("role"))) {
            Long positionId = body.has("positionId") && !body.get("positionId").isNull()
                    ? body.get("positionId").asLong() : null;
            MembershipRole role = null;
            if (body.has("role")) {
                try {
                    role = MembershipRole.valueOf(body.get("role").asString());
                } catch (IllegalArgumentException e) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "알 수 없는 역할입니다.");
                }
            }
            membership.changePosition(accounts.position(orgId, positionId, role));
        }
        if (body != null && body.has("departmentId")) {
            JsonNode node = body.get("departmentId");
            membership.assignDepartment(node.isNull() ? null : department(node.asLong(), orgId));
        }
        UserAccounts.requireDepartmentForEditor(membership.getRole(), membership.getDepartment());
        memberships.flush();
        audit.record(user.id(), orgId, "MEMBER_UPDATED", "MEMBERSHIP", membershipId, before, snapshot(membership));
        return MemberView.of(membership);
    }

    /** 소속 끊기. 끊은 사람은 이 그룹 화면에 들어올 수 없다(요청마다 활성 소속을 확인하므로 즉시 반영). */
    @Transactional
    public void remove(CurrentUser user, long membershipId) {
        Membership membership = managedMembership(user, membershipId);
        Map<String, Object> before = snapshot(membership);
        membership.deactivate();
        memberships.flush();
        audit.record(user.id(), membership.getOrganization().getId(), "MEMBER_REMOVED", "MEMBERSHIP", membershipId,
                before, snapshot(membership));
    }

    /** owner 가 바꿀 수 있는 소속인지: 같은 그룹, 활성, owner 소속이 아님, 자기 자신이 아님 */
    private Membership managedMembership(CurrentUser user, long membershipId) {
        Membership membership = memberships.findById(membershipId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        OrgAccess access = accessGuard.requireOwner(user, membership.getOrganization().getId());
        if (!membership.isActive()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "이미 끊긴 소속입니다.");
        }
        if (membership.getRole() == MembershipRole.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "owner 부여·회수는 서비스 관리자가 합니다.");
        }
        if (membership.getUser().getId() == access.userId()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "자기 소속은 바꿀 수 없습니다.");
        }
        return membership;
    }

    private Department department(Long departmentId, long orgId) {
        if (departmentId == null) {
            return null;
        }
        return departments.findActiveInOrg(departmentId, orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "이 그룹의 부서가 아닙니다."));
    }

    private static Map<String, Object> snapshot(Membership m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", m.getUser().getId());
        map.put("role", m.getRole());
        map.put("positionId", m.getPosition() == null ? null : m.getPosition().getId());
        map.put("departmentId", m.getDepartment() == null ? null : m.getDepartment().getId());
        map.put("active", m.isActive());
        return map;
    }
}
