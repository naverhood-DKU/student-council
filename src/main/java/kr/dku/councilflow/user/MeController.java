package kr.dku.councilflow.user;

import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.org.OrgType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final AppUserRepository users;
    private final MembershipRepository memberships;

    MeController(AppUserRepository users, MembershipRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    /** 내 정보와 활성 소속 목록. 화면의 조직 선택에 쓴다. */
    @GetMapping("/api/me")
    Me me(CurrentUser currentUser) {
        AppUser user = users.findById(currentUser.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        List<MembershipView> views = memberships.findActiveByUserId(user.getId()).stream()
                .map(m -> new MembershipView(
                        m.getOrganization().getId(),
                        m.getOrganization().getName(),
                        m.getOrganization().getType(),
                        m.getRole(),
                        m.getDepartment() == null ? null : m.getDepartment().getId(),
                        m.getDepartment() == null ? null : m.getDepartment().getName()))
                .toList();
        return new Me(user.getId(), user.getEmail(), user.getName(), views);
    }

    record Me(long id, String email, String name, List<MembershipView> memberships) {
    }

    record MembershipView(
            long orgId,
            String orgName,
            OrgType orgType,
            MembershipRole role,
            Long departmentId,
            String departmentName) {
    }
}
