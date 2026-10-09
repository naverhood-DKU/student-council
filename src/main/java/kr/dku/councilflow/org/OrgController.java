package kr.dku.councilflow.org;

import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orgs")
class OrgController {

    private final OrgService orgService;

    OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    /** 조직 정보·부서·내 역할. 그 조직의 활성 소속만 볼 수 있다. */
    @GetMapping("/{orgId}")
    OrgService.OrgDetail get(CurrentUser user, @PathVariable long orgId) {
        return orgService.getDetail(user, orgId);
    }

    /** 업무를 요청할 수 있는 그룹(자기 그룹 제외 모든 활성 그룹) */
    @GetMapping("/{orgId}/request-targets")
    List<OrgService.GroupOption> requestTargets(CurrentUser user, @PathVariable long orgId) {
        return orgService.requestTargets(user, orgId);
    }
}
