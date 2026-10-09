package kr.dku.councilflow.user;

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

@RestController
class MemberController {

    private final MemberService memberService;

    MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 그룹 구성원 목록(그룹 소속 또는 서비스 관리자) */
    @GetMapping("/api/orgs/{orgId}/members")
    List<MemberService.MemberView> list(CurrentUser user, @PathVariable long orgId) {
        return memberService.list(user, orgId);
    }

    /** 구성원 초대(owner). 역할은 EDITOR·VIEWER 만 */
    @PostMapping("/api/orgs/{orgId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    MemberService.MemberView add(CurrentUser user, @PathVariable long orgId,
                                 @Valid @RequestBody MemberService.AddMember request) {
        return memberService.add(user, orgId, request);
    }

    /** 역할·부서 바꾸기(owner, If-Match: version) */
    @PatchMapping("/api/memberships/{membershipId}")
    MemberService.MemberView update(CurrentUser user, @PathVariable long membershipId,
                                    @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                    @RequestBody JsonNode body) {
        return memberService.update(user, membershipId, ifMatch, body);
    }

    /** 소속 끊기(owner) */
    @DeleteMapping("/api/memberships/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(CurrentUser user, @PathVariable long membershipId) {
        memberService.remove(user, membershipId);
    }
}
