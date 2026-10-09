package kr.dku.councilflow.join;

import jakarta.validation.Valid;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JoinController {

    private final JoinService joinService;

    JoinController(JoinService joinService) {
        this.joinService = joinService;
    }

    /** 회원가입 + 가입 신청(로그인 없이). 소속은 회장이 승인해야 생긴다. */
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    JoinService.SignUpResult signUp(@Valid @RequestBody JoinService.SignUp request) {
        return joinService.signUp(request);
    }

    /** 로그인한 사람이 가입 코드로 다른 그룹에 신청 */
    @PostMapping("/api/me/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    JoinService.MyRequest apply(CurrentUser user, @Valid @RequestBody JoinService.Apply request) {
        return joinService.apply(user, request);
    }

    /** 그룹에 들어온 처리 전 신청(회장) */
    @GetMapping("/api/orgs/{orgId}/join-requests")
    List<JoinService.PendingRequest> pending(CurrentUser user, @PathVariable long orgId) {
        return joinService.pending(user, orgId);
    }

    /** 승인: { role: EDITOR | VIEWER, departmentId } (회장, If-Match: version) */
    @PostMapping("/api/join-requests/{requestId}/approve")
    JoinService.MyRequest approve(CurrentUser user, @PathVariable long requestId,
                                  @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                  @Valid @RequestBody JoinService.Approve request) {
        return joinService.approve(user, requestId, ifMatch, request);
    }

    /** 거절: { reason? } (회장, If-Match: version) */
    @PostMapping("/api/join-requests/{requestId}/reject")
    JoinService.MyRequest reject(CurrentUser user, @PathVariable long requestId,
                                 @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                 @Valid @RequestBody(required = false) JoinService.Reject request) {
        return joinService.reject(user, requestId, ifMatch, request);
    }
}
