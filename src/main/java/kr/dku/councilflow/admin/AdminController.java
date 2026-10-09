package kr.dku.councilflow.admin;

import jakarta.validation.Valid;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 관리자 전용 API. 관리자가 아니면 403. */
@RestController
class AdminController {

    private final AdminService adminService;

    AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 모든 그룹과 owner 목록 */
    @GetMapping("/api/admin/groups")
    List<AdminService.GroupView> groups(CurrentUser user) {
        return adminService.groups(user);
    }

    /** 그룹 만들기(기본 부서 포함) */
    @PostMapping("/api/admin/groups")
    @ResponseStatus(HttpStatus.CREATED)
    AdminService.GroupView create(CurrentUser user, @Valid @RequestBody AdminService.CreateGroup request) {
        return adminService.createGroup(user, request);
    }

    /** owner 부여. 없는 아이디면 이름·초기 비밀번호(8자 이상)로 계정을 만든다. */
    /** 가입 코드 정하기·바꾸기 { joinCode } */
    @PatchMapping("/api/admin/groups/{orgId}")
    AdminService.GroupView changeJoinCode(CurrentUser user, @PathVariable long orgId,
                                          @Valid @RequestBody AdminService.ChangeJoinCode request) {
        return adminService.changeJoinCode(user, orgId, request);
    }

    @PostMapping("/api/admin/groups/{orgId}/owners")
    AdminService.GroupView grantOwner(CurrentUser user, @PathVariable long orgId,
                                      @Valid @RequestBody AdminService.GrantOwner request) {
        return adminService.grantOwner(user, orgId, request);
    }

    /** owner 회수 */
    @DeleteMapping("/api/admin/groups/{orgId}/owners/{userId}")
    AdminService.GroupView revokeOwner(CurrentUser user, @PathVariable long orgId, @PathVariable long userId) {
        return adminService.revokeOwner(user, orgId, userId);
    }
}
