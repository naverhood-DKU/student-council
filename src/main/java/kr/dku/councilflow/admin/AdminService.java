package kr.dku.councilflow.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.org.DefaultDepartments;
import kr.dku.councilflow.org.DefaultPositions;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.org.OrgType;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import kr.dku.councilflow.org.PositionRepository;
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import kr.dku.councilflow.user.UserAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 관리자: 자치회(그룹)를 만들고 각 그룹에 owner(회장) 계정을 부여·회수한다.
 * 그룹 내용은 OrgAccessGuard 를 통해 읽기 전용으로만 볼 수 있고, 여기서 그룹 업무를 바꾸지는 않는다.
 */
@Service
public class AdminService {

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final MembershipRepository memberships;
    private final UserAccounts accounts;
    private final AuditService audit;

    AdminService(OrgAccessGuard accessGuard, OrganizationRepository organizations, DepartmentRepository departments,
                 PositionRepository positions, MembershipRepository memberships, UserAccounts accounts,
                 AuditService audit) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.departments = departments;
        this.positions = positions;
        this.memberships = memberships;
        this.accounts = accounts;
        this.audit = audit;
    }

    public record OwnerView(long userId, String loginId, String name) {
    }

    public record GroupView(long id, String name, String joinCode, OrgType type, Long parentId, String parentName,
                            String term, boolean active, long memberCount, List<OwnerView> owners) {
    }

    public record CreateGroup(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 30) String joinCode,
            @NotNull OrgType type,
            Long parentId,
            @NotBlank @Size(max = 20) String term) {
    }

    public record ChangeJoinCode(@NotBlank @Size(max = 30) String joinCode) {
    }

    public record GrantOwner(
            @NotBlank @Size(max = 255) @Pattern(regexp = "\\S+", message = "아이디에 공백을 넣을 수 없습니다.") String loginId,
            @Size(max = 50) String name,
            @Size(max = 100) String initialPassword) {
    }

    @Transactional(readOnly = true)
    public List<GroupView> groups(CurrentUser admin) {
        accessGuard.requireServiceAdmin(admin);
        return organizations.findAll().stream()
                .sorted(Comparator.comparing(Organization::getId))
                .map(this::view)
                .toList();
    }

    /** 그룹 만들기. 기본 부서(기획부·홍보부·재정부)와 기본 직책(부장·부원)을 함께 만든다. 단계·상위 그룹은 표시용이다. */
    @Transactional
    public GroupView createGroup(CurrentUser admin, CreateGroup request) {
        accessGuard.requireServiceAdmin(admin);
        Organization parent = request.parentId() == null ? null : organizations.findById(request.parentId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "상위 그룹을 찾을 수 없습니다."));
        String joinCode = Organization.normalizeJoinCode(request.joinCode());
        requireUnusedJoinCode(joinCode, null);
        Organization group = new Organization(request.name().strip(), request.type(), parent, request.term().strip());
        group.changeJoinCode(joinCode);
        organizations.save(group);
        departments.saveAll(DefaultDepartments.of(group));
        positions.saveAll(DefaultPositions.of(group));
        audit.record(admin.id(), group.getId(), "GROUP_CREATED", "ORGANIZATION", group.getId(), null,
                Map.of("name", group.getName(), "type", group.getType(), "joinCode", group.getJoinCode()));
        return view(group);
    }

    /** 가입 코드 정하기·바꾸기. 바꾸면 예전 코드로는 가입 신청할 수 없다(이미 들어온 신청은 그대로). */
    @Transactional
    public GroupView changeJoinCode(CurrentUser admin, long orgId, ChangeJoinCode request) {
        accessGuard.requireServiceAdmin(admin);
        Organization group = organizations.findById(orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "그룹을 찾을 수 없습니다."));
        String before = group.getJoinCode();
        // 바꾸기 전에 확인한다(바꾼 뒤 조회하면 그 변경이 먼저 DB로 나가 고유 제약 오류가 된다).
        String joinCode = Organization.normalizeJoinCode(request.joinCode());
        requireUnusedJoinCode(joinCode, orgId);
        group.changeJoinCode(joinCode);
        organizations.flush();
        audit.record(admin.id(), orgId, "JOIN_CODE_CHANGED", "ORGANIZATION", orgId,
                before == null ? null : Map.of("joinCode", before), Map.of("joinCode", group.getJoinCode()));
        return view(group);
    }

    private void requireUnusedJoinCode(String code, Long exceptOrgId) {
        organizations.findByJoinCode(code)
                .filter(other -> !other.getId().equals(exceptOrgId))
                .ifPresent(other -> {
                    throw new ApiException(ErrorCode.DUPLICATE, "다른 그룹이 쓰는 가입 코드입니다.");
                });
    }

    /**
     * owner 부여. 아이디가 있으면 그 계정을, 없으면 이름·초기 비밀번호로 새 계정을 만든다.
     * 이미 그 그룹의 editor·viewer 면 owner 로 올린다. 한 그룹에 owner 가 여러 명일 수 있다.
     */
    @Transactional
    public GroupView grantOwner(CurrentUser admin, long orgId, GrantOwner request) {
        accessGuard.requireServiceAdmin(admin);
        Organization group = organizations.findById(orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "그룹을 찾을 수 없습니다."));
        AppUser owner = accounts.findOrCreate(request.loginId(), request.name(), request.initialPassword());
        Membership membership = accounts.grantOwner(owner, group);
        memberships.flush();
        audit.record(admin.id(), orgId, "OWNER_GRANTED", "MEMBERSHIP", membership.getId(), null, snapshot(membership));
        return view(group);
    }

    /** owner 회수: 그 그룹의 owner 소속을 끊는다. 계정은 남는다. */
    @Transactional
    public GroupView revokeOwner(CurrentUser admin, long orgId, long userId) {
        accessGuard.requireServiceAdmin(admin);
        Organization group = organizations.findById(orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "그룹을 찾을 수 없습니다."));
        Membership membership = memberships.findActiveByUserIdAndOrgId(userId, orgId).stream()
                .filter(m -> m.getRole() == MembershipRole.OWNER)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "이 그룹의 owner 가 아닙니다."));
        Map<String, Object> before = snapshot(membership);
        membership.deactivate();
        memberships.flush();
        audit.record(admin.id(), orgId, "OWNER_REVOKED", "MEMBERSHIP", membership.getId(), before,
                snapshot(membership));
        return view(group);
    }

    private GroupView view(Organization group) {
        List<Membership> active = memberships.findActiveByOrgId(group.getId());
        List<OwnerView> owners = active.stream()
                .filter(m -> m.getRole() == MembershipRole.OWNER)
                .map(m -> new OwnerView(m.getUser().getId(), m.getUser().getEmail(), m.getUser().getName()))
                .toList();
        Organization parent = group.getParent();
        return new GroupView(group.getId(), group.getName(), group.getJoinCode(), group.getType(),
                parent == null ? null : parent.getId(), parent == null ? null : parent.getName(), group.getTerm(),
                group.isActive(), active.size(), owners);
    }

    private static Map<String, Object> snapshot(Membership m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", m.getUser().getId());
        map.put("role", m.getRole());
        map.put("active", m.isActive());
        return map;
    }
}
