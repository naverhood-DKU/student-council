package kr.dku.councilflow.user;

import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.join.JoinService;
import kr.dku.councilflow.org.OrgType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final JoinService joinService;

    MeController(AppUserRepository users, MembershipRepository memberships, JoinService joinService) {
        this.users = users;
        this.memberships = memberships;
        this.joinService = joinService;
    }

    /** 내 정보, 활성 소속, 처리 전·거절된 가입 신청. 화면의 그룹 선택에 쓴다. */
    @GetMapping("/api/me")
    Me me(CurrentUser currentUser) {
        AppUser user = users.findById(currentUser.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        List<MembershipView> views = memberships.findActiveByUserId(user.getId()).stream()
                .map(m -> new MembershipView(
                        m.getOrganization().getId(),
                        m.getOrganization().getName(),
                        m.getOrganization().getType(),
                        m.getRole(),
                        m.getPosition() == null ? null : m.getPosition().getName(),
                        m.getDepartment() == null ? null : m.getDepartment().getId(),
                        m.getDepartment() == null ? null : m.getDepartment().getName()))
                .toList();
        return new Me(user.getId(), user.getEmail(), user.getName(), user.isServiceAdmin(), views,
                joinService.mine(user.getId()));
    }

    /** serviceAdmin 이면 관리자 화면과 모든 그룹 읽기 전용 열람을 쓸 수 있다. */
    record Me(long id, String email, String name, boolean serviceAdmin, List<MembershipView> memberships,
              List<JoinService.MyRequest> joinRequests) {
    }

    record MembershipView(
            long orgId,
            String orgName,
            OrgType orgType,
            MembershipRole role,
            /** 직책 이름(회장은 null) */
            String positionName,
            Long departmentId,
            String departmentName) {
    }
}
