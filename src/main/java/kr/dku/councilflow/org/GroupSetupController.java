package kr.dku.councilflow.org;

import jakarta.validation.Valid;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 그룹 구성 설정: 부서·직책. 보기는 구성원 누구나, 바꾸기는 회장만. */
@RestController
class GroupSetupController {

    private final GroupSetupService setup;

    GroupSetupController(GroupSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/api/orgs/{orgId}/departments")
    List<GroupSetupService.DepartmentSetting> departments(CurrentUser user, @PathVariable long orgId) {
        return setup.departments(user, orgId);
    }

    /** 부서 만들기 { name, roleKeywords? } */
    @PostMapping("/api/orgs/{orgId}/departments")
    @ResponseStatus(HttpStatus.CREATED)
    GroupSetupService.DepartmentSetting addDepartment(CurrentUser user, @PathVariable long orgId,
                                                      @Valid @RequestBody GroupSetupService.NewDepartment request) {
        return setup.addDepartment(user, orgId, request);
    }

    /** 부서 이름·역할 키워드 { name?, roleKeywords? } (If-Match: version) */
    @PatchMapping("/api/departments/{departmentId}")
    GroupSetupService.DepartmentSetting updateDepartment(CurrentUser user, @PathVariable long departmentId,
                                                         @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                                         @RequestBody JsonNode body) {
        return setup.updateDepartment(user, departmentId, ifMatch, body);
    }

    /** 부서 없애기(숨김) */
    @DeleteMapping("/api/departments/{departmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeDepartment(CurrentUser user, @PathVariable long departmentId) {
        setup.removeDepartment(user, departmentId);
    }

    @GetMapping("/api/orgs/{orgId}/positions")
    List<GroupSetupService.PositionSetting> positions(CurrentUser user, @PathVariable long orgId) {
        return setup.positions(user, orgId);
    }

    /** 직책 만들기 { name, level: EDITOR | VIEWER } */
    @PostMapping("/api/orgs/{orgId}/positions")
    @ResponseStatus(HttpStatus.CREATED)
    GroupSetupService.PositionSetting addPosition(CurrentUser user, @PathVariable long orgId,
                                                  @Valid @RequestBody GroupSetupService.NewPosition request) {
        return setup.addPosition(user, orgId, request);
    }

    /** 직책 이름·권한 수준 { name?, level? } (If-Match: version) */
    @PatchMapping("/api/positions/{positionId}")
    GroupSetupService.PositionSetting updatePosition(CurrentUser user, @PathVariable long positionId,
                                                     @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                                     @RequestBody JsonNode body) {
        return setup.updatePosition(user, positionId, ifMatch, body);
    }

    @DeleteMapping("/api/positions/{positionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePosition(CurrentUser user, @PathVariable long positionId) {
        setup.removePosition(user, positionId);
    }
}
