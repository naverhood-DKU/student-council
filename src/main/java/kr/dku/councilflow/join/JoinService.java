package kr.dku.councilflow.join;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
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
import kr.dku.councilflow.user.AppUser;
import kr.dku.councilflow.user.AppUserRepository;
import kr.dku.councilflow.user.Membership;
import kr.dku.councilflow.user.MembershipRepository;
import kr.dku.councilflow.user.MembershipRole;
import kr.dku.councilflow.user.UserAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입과 그룹 가입 신청.
 * - 누구나 아이디·이름·비밀번호·가입 코드로 가입한다. 계정은 바로 생기지만 그룹 소속은 없고, 그 그룹에 가입 신청이 간다.
 * - 이미 계정이 있으면 로그인한 뒤 가입 코드로 다른 그룹에 신청한다.
 * - 그룹 회장(owner)이 직책(권한 수준이 따라옴)과 부서를 정해 승인하면 소속이 생긴다. 회장 자리는 서비스 관리자만 준다.
 */
@Service
public class JoinService {

    private final OrganizationRepository organizations;
    private final DepartmentRepository departments;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final JoinRequestRepository requests;
    private final UserAccounts accounts;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final Clock clock;

    JoinService(OrganizationRepository organizations, DepartmentRepository departments, AppUserRepository users,
                MembershipRepository memberships, JoinRequestRepository requests, UserAccounts accounts,
                OrgAccessGuard accessGuard, AuditService audit, Clock clock) {
        this.organizations = organizations;
        this.departments = departments;
        this.users = users;
        this.memberships = memberships;
        this.requests = requests;
        this.accounts = accounts;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.clock = clock;
    }

    public record SignUp(
            @NotBlank @Size(max = 255) @Pattern(regexp = "\\S+", message = "아이디에 공백을 넣을 수 없습니다.") String loginId,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(max = 100) String password,
            @NotBlank @Size(max = 30) String groupCode) {
    }

    public record SignUpResult(String loginId, String groupName, JoinRequestStatus status) {
    }

    public record Apply(@NotBlank @Size(max = 30) String groupCode) {
    }

    /** 내 신청(처리 전·거절) */
    public record MyRequest(long id, long orgId, String orgName, JoinRequestStatus status, String rejectReason,
                            Instant requestedAt) {

        static MyRequest of(JoinRequest r) {
            return new MyRequest(r.getId(), r.getOrganization().getId(), r.getOrganization().getName(),
                    r.getStatus(), r.getRejectReason(), r.getUpdatedAt());
        }
    }

    /** 회장이 보는 처리 전 신청 */
    public record PendingRequest(long id, long userId, String loginId, String name, Instant requestedAt,
                                 long version) {
    }

    /** 승인: 직책과 부서. positionId 대신 role(EDITOR·VIEWER)을 보내면 그 권한 수준의 첫 직책 */
    public record Approve(Long positionId, MembershipRole role, Long departmentId) {
    }

    public record Reject(@Size(max = 500) String reason) {
    }

    /** 회원가입 + 가입 신청. 아이디가 이미 있으면 409(그 아이디로 로그인해서 신청하면 된다). */
    @Transactional
    public SignUpResult signUp(SignUp request) {
        Organization group = joinableGroup(request.groupCode());
        String loginId = AppUser.normalizeEmail(request.loginId());
        if (users.findByEmail(loginId).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE, "이미 쓰는 아이디입니다. 내 계정이면 로그인한 뒤 가입 신청하세요.");
        }
        AppUser user = users.save(new AppUser(loginId, accounts.encodeNew(request.password()), request.name().strip()));
        JoinRequest joinRequest = requests.save(new JoinRequest(user, group));
        requests.flush();
        audit.record(user.getId(), group.getId(), "JOIN_REQUESTED", "JOIN_REQUEST", joinRequest.getId(), null,
                snapshot(joinRequest));
        return new SignUpResult(user.getEmail(), group.getName(), joinRequest.getStatus());
    }

    /** 이미 계정이 있는 사람이 다른 그룹에 가입 신청 */
    @Transactional
    public MyRequest apply(CurrentUser current, Apply request) {
        AppUser user = users.findById(current.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        Organization group = joinableGroup(request.groupCode());
        if (!memberships.findActiveByUserIdAndOrgId(user.getId(), group.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE, "이미 이 그룹의 구성원입니다.");
        }
        JoinRequest joinRequest = requests.findByUserIdAndOrganizationId(user.getId(), group.getId())
                .map(existing -> {
                    if (existing.getStatus() == JoinRequestStatus.PENDING) {
                        throw new ApiException(ErrorCode.DUPLICATE, "이미 가입 신청을 했습니다. 회장의 승인을 기다려 주세요.");
                    }
                    existing.reopen();
                    return existing;
                })
                .orElseGet(() -> requests.save(new JoinRequest(user, group)));
        requests.flush();
        audit.record(user.getId(), group.getId(), "JOIN_REQUESTED", "JOIN_REQUEST", joinRequest.getId(), null,
                snapshot(joinRequest));
        return MyRequest.of(joinRequest);
    }

    @Transactional(readOnly = true)
    public List<MyRequest> mine(long userId) {
        return requests.findOpenByUserId(userId).stream().map(MyRequest::of).toList();
    }

    /** 그룹에 들어온 처리 전 신청. 회장만 본다. */
    @Transactional(readOnly = true)
    public List<PendingRequest> pending(CurrentUser user, long orgId) {
        accessGuard.requireOwner(user, orgId);
        return requests.findPendingByOrgId(orgId).stream()
                .map(r -> new PendingRequest(r.getId(), r.getUser().getId(), r.getUser().getEmail(),
                        r.getUser().getName(), r.getUpdatedAt(), r.getVersion()))
                .toList();
    }

    /** 승인: 회장이 역할(부장·부원)과 부서를 정하면 그 그룹 소속이 생긴다. (If-Match: version) */
    @Transactional
    public MyRequest approve(CurrentUser user, long requestId, String ifMatch, Approve request) {
        JoinRequest joinRequest = findRequest(requestId);
        OrgAccess access = accessGuard.requireOwner(user, joinRequest.getOrganization().getId());
        IfMatch.check(ifMatch, joinRequest.getVersion());
        long orgId = access.orgId();
        Position position = accounts.position(orgId, request.positionId(), request.role());
        Department department = department(request.departmentId(), orgId);
        UserAccounts.requireDepartmentForEditor(position.getLevel(), department);
        if (!memberships.findActiveByUserIdAndOrgId(joinRequest.getUser().getId(), orgId).isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "이미 이 그룹의 구성원입니다. 신청은 거절해서 정리해 주세요.");
        }
        Map<String, Object> before = snapshot(joinRequest);
        joinRequest.approve(access.userId(), Instant.now(clock));
        Membership membership = accounts.grant(joinRequest.getUser(), joinRequest.getOrganization(), position,
                department);
        requests.flush();
        Map<String, Object> after = snapshot(joinRequest);
        after.put("membershipId", membership.getId());
        after.put("role", membership.getRole());
        after.put("positionId", position.getId());
        after.put("departmentId", department == null ? null : department.getId());
        audit.record(access.userId(), orgId, "JOIN_APPROVED", "JOIN_REQUEST", requestId, before, after);
        return MyRequest.of(joinRequest);
    }

    /** 거절. 사유는 신청한 사람에게 보인다. (If-Match: version) */
    @Transactional
    public MyRequest reject(CurrentUser user, long requestId, String ifMatch, Reject request) {
        JoinRequest joinRequest = findRequest(requestId);
        OrgAccess access = accessGuard.requireOwner(user, joinRequest.getOrganization().getId());
        IfMatch.check(ifMatch, joinRequest.getVersion());
        Map<String, Object> before = snapshot(joinRequest);
        joinRequest.reject(access.userId(), Instant.now(clock), request == null ? null : request.reason());
        requests.flush();
        audit.record(access.userId(), access.orgId(), "JOIN_REJECTED", "JOIN_REQUEST", requestId, before,
                snapshot(joinRequest));
        return MyRequest.of(joinRequest);
    }

    private JoinRequest findRequest(long requestId) {
        return requests.findById(requestId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }

    /** 가입 코드로 찾은 활성 그룹. 없거나 닫힌 그룹이면 400. */
    private Organization joinableGroup(String code) {
        return organizations.findByJoinCode(Organization.normalizeJoinCode(code))
                .filter(Organization::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "가입 코드에 맞는 그룹이 없습니다."));
    }

    private Department department(Long departmentId, long orgId) {
        if (departmentId == null) {
            return null;
        }
        return departments.findActiveInOrg(departmentId, orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "이 그룹의 부서가 아닙니다."));
    }

    private static Map<String, Object> snapshot(JoinRequest r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", r.getUser().getId());
        map.put("status", r.getStatus());
        map.put("rejectReason", r.getRejectReason());
        return map;
    }
}
