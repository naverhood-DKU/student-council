package kr.dku.councilflow.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.decision.TaskRepository;
import kr.dku.councilflow.decision.TaskStatus;
import kr.dku.councilflow.delivery.DeliveryRepository;
import kr.dku.councilflow.delivery.DeliveryStatus;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 그룹 구성 설정: 회장(owner)이 부서와 직책을 만들고 고친다. 보는 것은 그룹 구성원 누구나.
 *
 * 부서
 * - 이름과 역할 키워드(AI가 담당 부서를 추천할 때 쓴다)를 정한다.
 * - 없애면 지우지 않고 숨긴다. 구성원이 있거나 끝나지 않은 업무·받은 요청이 있으면 없앨 수 없다.
 * - 없앤 부서와 같은 이름으로 다시 만들면 그 부서를 되살린다(지난 기록이 이어진다).
 *
 * 직책
 * - 이름과 권한 수준(EDITOR 부장급 / VIEWER 부원급)을 정한다. 구성원의 권한은 직책의 권한 수준을 따른다.
 * - 권한 수준을 바꾸면 그 직책을 가진 구성원 권한도 같이 바뀐다. 부장급은 부서가 있어야 한다.
 * - 그 직책을 가진 구성원이 있거나 마지막 직책이면 지울 수 없다.
 */
@Service
public class GroupSetupService {

    private static final int MAX_NAME = 30;
    private static final EnumSet<TaskStatus> FINISHED_TASKS = EnumSet.of(TaskStatus.DONE, TaskStatus.CANCELLED);
    private static final EnumSet<DeliveryStatus> FINISHED_DELIVERIES =
            EnumSet.of(DeliveryStatus.DONE, DeliveryStatus.NOT_APPLICABLE, DeliveryStatus.CANCELLED);

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final MembershipRepository memberships;
    private final TaskRepository tasks;
    private final DeliveryRepository deliveries;
    private final AuditService audit;

    GroupSetupService(OrgAccessGuard accessGuard, OrganizationRepository organizations,
                      DepartmentRepository departments, PositionRepository positions,
                      MembershipRepository memberships, TaskRepository tasks, DeliveryRepository deliveries,
                      AuditService audit) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.departments = departments;
        this.positions = positions;
        this.memberships = memberships;
        this.tasks = tasks;
        this.deliveries = deliveries;
        this.audit = audit;
    }

    public record DepartmentSetting(long id, String name, String roleKeywords, long memberCount, long openTaskCount,
                                    long version) {
    }

    public record PositionSetting(long id, String name, MembershipRole level, int sortOrder, long memberCount,
                                  long version) {
    }

    public record NewDepartment(@NotBlank @Size(max = MAX_NAME) String name, @Size(max = 500) String roleKeywords) {
    }

    public record NewPosition(@NotBlank @Size(max = MAX_NAME) String name, @NotNull MembershipRole level) {
    }

    // ---- 부서 ----

    @Transactional(readOnly = true)
    public List<DepartmentSetting> departments(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        Map<Long, Long> people = memberships.findActiveByOrgId(orgId).stream()
                .filter(m -> m.getDepartment() != null)
                .collect(Collectors.groupingBy(m -> m.getDepartment().getId(), Collectors.counting()));
        return departments.findByOrganizationIdAndActiveTrueOrderById(orgId).stream()
                .map(d -> departmentSetting(d, people.getOrDefault(d.getId(), 0L)))
                .toList();
    }

    /** 부서 만들기. 없앤 부서와 이름이 같으면 그 부서를 되살린다. */
    @Transactional
    public DepartmentSetting addDepartment(CurrentUser user, long orgId, NewDepartment request) {
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        Organization org = organizations.findById(orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        String name = name(request.name(), "부서");
        String keywords = keywords(request.roleKeywords());
        Department department = departments.findByOrganizationIdOrderById(orgId).stream()
                .filter(d -> d.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(existing -> {
                    if (existing.isActive()) {
                        throw new ApiException(ErrorCode.DUPLICATE, "이미 있는 부서 이름입니다.");
                    }
                    existing.reactivate(keywords);
                    return existing;
                })
                .orElseGet(() -> departments.save(new Department(org, name, keywords)));
        departments.flush();
        audit.record(access.userId(), orgId, "DEPARTMENT_ADDED", "DEPARTMENT", department.getId(), null,
                departmentSnapshot(department));
        return departmentSetting(department, 0);
    }

    /** 이름·역할 키워드 바꾸기 { name?, roleKeywords? } (If-Match: version) */
    @Transactional
    public DepartmentSetting updateDepartment(CurrentUser user, long departmentId, String ifMatch, JsonNode body) {
        Department department = activeDepartment(departmentId);
        long orgId = department.getOrganization().getId();
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        IfMatch.check(ifMatch, department.getVersion());
        Map<String, Object> before = departmentSnapshot(department);
        if (body != null && body.has("name")) {
            String name = name(text(body.get("name")), "부서");
            boolean taken = departments.findByOrganizationIdOrderById(orgId).stream()
                    .anyMatch(d -> d.getId() != departmentId && d.getName().equalsIgnoreCase(name));
            if (taken) {
                throw new ApiException(ErrorCode.DUPLICATE, "이미 있는(또는 없앤) 부서 이름입니다.");
            }
            department.rename(name);
        }
        if (body != null && body.has("roleKeywords")) {
            department.changeRoleKeywords(keywords(text(body.get("roleKeywords"))));
        }
        departments.flush();
        audit.record(access.userId(), orgId, "DEPARTMENT_UPDATED", "DEPARTMENT", departmentId, before,
                departmentSnapshot(department));
        return departmentSetting(department, countMembers(orgId, departmentId));
    }

    /** 부서 없애기(숨김). 구성원·끝나지 않은 업무·받은 요청이 남아 있으면 409. */
    @Transactional
    public void removeDepartment(CurrentUser user, long departmentId) {
        Department department = activeDepartment(departmentId);
        long orgId = department.getOrganization().getId();
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        long people = countMembers(orgId, departmentId);
        if (people > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "이 부서에 구성원이 " + people + "명 있습니다. 먼저 다른 부서로 옮겨 주세요.");
        }
        long openTasks = tasks.countByDepartmentIdAndStatusNotIn(departmentId, FINISHED_TASKS);
        if (openTasks > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "이 부서가 맡은 끝나지 않은 업무가 " + openTasks + "건 있습니다. 담당을 옮기거나 끝낸 뒤 없애 주세요.");
        }
        long openDeliveries = deliveries.countByInternalDepartmentIdAndStatusNotIn(departmentId, FINISHED_DELIVERIES);
        if (openDeliveries > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "이 부서가 맡은 받은 요청이 " + openDeliveries + "건 있습니다. 담당을 옮기거나 끝낸 뒤 없애 주세요.");
        }
        if (departments.findByOrganizationIdAndActiveTrueOrderById(orgId).size() <= 1) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "부서가 하나는 있어야 합니다.");
        }
        Map<String, Object> before = departmentSnapshot(department);
        department.deactivate();
        departments.flush();
        audit.record(access.userId(), orgId, "DEPARTMENT_REMOVED", "DEPARTMENT", departmentId, before,
                departmentSnapshot(department));
    }

    // ---- 직책 ----

    @Transactional(readOnly = true)
    public List<PositionSetting> positions(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        Map<Long, Long> people = memberships.findActiveByOrgId(orgId).stream()
                .filter(m -> m.getPosition() != null)
                .collect(Collectors.groupingBy(m -> m.getPosition().getId(), Collectors.counting()));
        return positions.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId).stream()
                .map(p -> positionSetting(p, people.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Transactional
    public PositionSetting addPosition(CurrentUser user, long orgId, NewPosition request) {
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        Organization org = organizations.findById(orgId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        String name = name(request.name(), "직책");
        requireLevel(request.level());
        List<Position> existing = positions.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name))) {
            throw new ApiException(ErrorCode.DUPLICATE, "이미 있는 직책 이름입니다.");
        }
        int nextOrder = existing.stream().map(Position::getSortOrder).max(Comparator.naturalOrder()).orElse(0) + 1;
        Position position = positions.saveAndFlush(new Position(org, name, request.level(), nextOrder));
        audit.record(access.userId(), orgId, "POSITION_ADDED", "POSITION", position.getId(), null,
                positionSnapshot(position));
        return positionSetting(position, 0);
    }

    /**
     * 이름·권한 수준 바꾸기 { name?, level? } (If-Match: version)
     * 권한 수준을 바꾸면 그 직책을 가진 구성원의 권한도 바뀐다. 부장급으로 올리려면 모두 부서가 있어야 한다.
     */
    @Transactional
    public PositionSetting updatePosition(CurrentUser user, long positionId, String ifMatch, JsonNode body) {
        Position position = positions.findById(positionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        long orgId = position.getOrganization().getId();
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        IfMatch.check(ifMatch, position.getVersion());
        Map<String, Object> before = positionSnapshot(position);
        List<Membership> holders = memberships.findActiveByPositionId(positionId);
        if (body != null && body.has("name")) {
            String name = name(text(body.get("name")), "직책");
            boolean taken = positions.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId).stream()
                    .anyMatch(p -> p.getId() != positionId && p.getName().equalsIgnoreCase(name));
            if (taken) {
                throw new ApiException(ErrorCode.DUPLICATE, "이미 있는 직책 이름입니다.");
            }
            position.rename(name);
        }
        if (body != null && body.has("level")) {
            MembershipRole level = level(body.get("level"));
            if (level == MembershipRole.EDITOR) {
                long withoutDepartment = holders.stream().filter(m -> m.getDepartment() == null).count();
                if (withoutDepartment > 0) {
                    throw new ApiException(ErrorCode.INVALID_TRANSITION, "이 직책을 가진 구성원 중 부서가 없는 사람이 "
                            + withoutDepartment + "명 있어 부장급으로 바꿀 수 없습니다. 먼저 부서를 정해 주세요.");
                }
            }
            position.changeLevel(level);
            holders.forEach(Membership::followPositionLevel);
        }
        positions.flush();
        audit.record(access.userId(), orgId, "POSITION_UPDATED", "POSITION", positionId, before,
                positionSnapshot(position));
        return positionSetting(position, holders.size());
    }

    @Transactional
    public void removePosition(CurrentUser user, long positionId) {
        Position position = positions.findById(positionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        long orgId = position.getOrganization().getId();
        OrgAccess access = accessGuard.requireOwner(user, orgId);
        int holders = memberships.findActiveByPositionId(positionId).size();
        if (holders > 0) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "이 직책을 가진 구성원이 " + holders + "명 있습니다. 먼저 다른 직책으로 바꿔 주세요.");
        }
        if (positions.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId).size() <= 1) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "직책이 하나는 있어야 합니다.");
        }
        Map<String, Object> before = positionSnapshot(position);
        memberships.clearPositionOfInactive(positionId);
        positions.delete(position);
        positions.flush();
        audit.record(access.userId(), orgId, "POSITION_REMOVED", "POSITION", positionId, before, null);
    }

    // ---- 공통 ----

    private Department activeDepartment(long departmentId) {
        return departments.findById(departmentId)
                .filter(Department::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    private long countMembers(long orgId, long departmentId) {
        return memberships.findActiveByOrgId(orgId).stream()
                .filter(m -> m.getDepartment() != null && m.getDepartment().getId() == departmentId)
                .count();
    }

    private DepartmentSetting departmentSetting(Department d, long memberCount) {
        return new DepartmentSetting(d.getId(), d.getName(), d.getRoleKeywords(), memberCount,
                tasks.countByDepartmentIdAndStatusNotIn(d.getId(), FINISHED_TASKS), d.getVersion());
    }

    private static PositionSetting positionSetting(Position p, long memberCount) {
        return new PositionSetting(p.getId(), p.getName(), p.getLevel(), p.getSortOrder(), memberCount, p.getVersion());
    }

    private static String name(String raw, String what) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, what + " 이름은 1~" + MAX_NAME + "자로 적어 주세요.");
        }
        return name;
    }

    /** 역할 키워드: 쉼표로 나눠 앞뒤 공백·빈 칸을 지운다. 비면 null. */
    private static String keywords(String raw) {
        if (raw == null) {
            return null;
        }
        String joined = List.of(raw.split(",")).stream()
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "역할 키워드는 500자 이하로 적어 주세요.");
        }
        return joined.isEmpty() ? null : joined;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static MembershipRole level(JsonNode node) {
        MembershipRole level;
        try {
            level = MembershipRole.valueOf(node.asString());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "권한 수준은 EDITOR(부장급) 또는 VIEWER(부원급)입니다.");
        }
        requireLevel(level);
        return level;
    }

    private static void requireLevel(MembershipRole level) {
        if (level == MembershipRole.OWNER) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "회장은 직책으로 만들 수 없습니다. 서비스 관리자가 정합니다.");
        }
    }

    private static Map<String, Object> departmentSnapshot(Department d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.getName());
        map.put("roleKeywords", d.getRoleKeywords());
        map.put("active", d.isActive());
        return map;
    }

    private static Map<String, Object> positionSnapshot(Position p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", p.getName());
        map.put("level", p.getLevel());
        return map;
    }
}
