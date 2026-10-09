package kr.dku.councilflow.user;

import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.Position;
import kr.dku.councilflow.org.PositionRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 만들기와 그룹 소속 부여. 서비스 관리자(회장 부여)와 회장(직책을 정해 초대·가입 승인)이 같이 쓴다.
 * 비밀번호는 BCrypt 해시로만 저장하고, 새로 정하는 비밀번호는 8자 이상이어야 한다.
 */
@Component
public class UserAccounts {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PositionRepository positions;
    private final PasswordEncoder passwordEncoder;

    UserAccounts(AppUserRepository users, MembershipRepository memberships, PositionRepository positions,
                 PasswordEncoder passwordEncoder) {
        this.users = users;
        this.memberships = memberships;
        this.positions = positions;
        this.passwordEncoder = passwordEncoder;
    }

    /** 아이디가 있으면 그 계정을, 없으면 이름·초기 비밀번호로 새 계정을 만든다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppUser findOrCreate(String loginId, String name, String initialPassword) {
        String normalized = AppUser.normalizeEmail(loginId);
        return users.findByEmail(normalized).orElseGet(() -> {
            if (name == null || name.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "새 계정이라 이름이 필요합니다.");
            }
            return users.save(new AppUser(normalized, encodeNew(initialPassword), name.strip()));
        });
    }

    /** 회장(owner) 자리를 준다. 서비스 관리자만 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Membership grantOwner(AppUser user, Organization org) {
        return upsert(user, org, null, null);
    }

    /** 직책(부장급·부원급)을 준다. 권한은 직책의 권한 수준을 따르고, 부장급은 부서가 있어야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Membership grant(AppUser user, Organization org, Position position, Department department) {
        requireDepartmentForEditor(position.getLevel(), department);
        return upsert(user, org, position, department);
    }

    /**
     * 이미 활성 소속이면 직책·부서를 바꾸고, 같은 임기에 끊긴 소속이 있으면 다시 살린다.
     * 한 그룹·한 임기에 한 사람은 소속 하나만 갖는다. position 이 null 이면 회장.
     */
    private Membership upsert(AppUser user, Organization org, Position position, Department department) {
        return memberships.findByUserIdAndOrganizationIdAndTerm(user.getId(), org.getId(), org.getTerm())
                .map(existing -> {
                    existing.reactivate(position, department);
                    return existing;
                })
                .orElseGet(() -> memberships.save(position == null
                        ? Membership.owner(user, org, org.getTerm())
                        : Membership.withPosition(user, org, position, department, org.getTerm())));
    }

    /**
     * 고른 직책. positionId 가 있으면 그 그룹의 직책을, 없으면 role(EDITOR·VIEWER)에 맞는 첫 직책을 쓴다.
     * (role 은 직책이 생기기 전 방식과 맞추려고 남겨 둔 입력이다.)
     */
    public Position position(long orgId, Long positionId, MembershipRole role) {
        if (positionId != null) {
            return positions.findById(positionId)
                    .filter(p -> p.getOrganization().getId() == orgId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "이 그룹의 직책이 아닙니다."));
        }
        if (role == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "직책을 골라 주세요.");
        }
        requireNotOwnerRole(role);
        return positions.findByOrganizationIdOrderBySortOrderAscIdAsc(orgId).stream()
                .filter(p -> p.getLevel() == role)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "그 권한 수준의 직책이 없습니다. 직책 설정에서 먼저 만들어 주세요."));
    }

    /** 회장이 정해 줄 수 없는 역할이면 403. owner(회장)는 서비스 관리자만 준다. */
    public static void requireNotOwnerRole(MembershipRole role) {
        if (role == MembershipRole.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "owner 는 서비스 관리자가 부여합니다.");
        }
    }

    /** 부장(editor)은 일할 부서가 있어야 한다. */
    public static void requireDepartmentForEditor(MembershipRole role, Department department) {
        if (role == MembershipRole.EDITOR && department == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "부장(editor)은 맡을 부서를 정해야 합니다.");
        }
    }

    public String encodeNew(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상으로 정해 주세요.");
        }
        return passwordEncoder.encode(password);
    }

    public boolean matches(String raw, AppUser user) {
        return passwordEncoder.matches(raw, user.getPasswordHash());
    }
}
