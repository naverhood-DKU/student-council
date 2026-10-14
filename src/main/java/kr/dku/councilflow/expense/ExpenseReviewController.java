package kr.dku.councilflow.expense;

import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.job.JobView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 지출 절차 검토: 후보 조항 찾기 → AI 안내(작업 큐) → 체크리스트 → 검토 완료. */
@RestController
class ExpenseReviewController {

    private final ExpenseReviewService service;
    private final TaskRuleChecks ruleChecks;

    ExpenseReviewController(ExpenseReviewService service, TaskRuleChecks ruleChecks) {
        this.service = service;
        this.ruleChecks = ruleChecks;
    }

    /**
     * 업무 카드 하나의 규정 확인 표시(돈과 관련된 카드만). 대상이 아니면 204.
     * 화면은 AI 안내가 끝날 때까지(badge=PENDING) 이 주소를 다시 불러 배지를 바꾼다.
     */
    @GetMapping("/api/tasks/{taskId}/rule-check")
    ResponseEntity<TaskRuleChecks.RuleCheckView> ruleCheck(CurrentUser user, @PathVariable long taskId) {
        return ruleChecks.get(user, taskId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** { orgId, decisionId?, item?, amount?, fundSource?, plannedDate?, note?, keywords? } */
    @PostMapping("/api/expense-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    ExpenseReviewService.ReviewView create(CurrentUser user, @RequestBody ExpenseReviewService.CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping("/api/orgs/{orgId}/expense-reviews")
    List<ExpenseReviewService.ReviewSummary> list(CurrentUser user, @PathVariable long orgId) {
        return service.list(user, orgId);
    }

    @GetMapping("/api/expense-reviews/{reviewId}")
    ExpenseReviewService.ReviewView get(CurrentUser user, @PathVariable long reviewId) {
        return service.get(user, reviewId);
    }

    /** AI 안내 다시 받기(회장·부장급). 이미 대기·진행 중이면 그 작업을 돌려준다. */
    @PostMapping("/api/expense-reviews/{reviewId}/guidance")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView guidance(CurrentUser user, @PathVariable long reviewId) {
        return service.requestGuidance(user, reviewId);
    }

    /** 검토 완료(회장, If-Match 필수). AI 안내가 끝난 뒤 한 번만. */
    @PostMapping("/api/expense-reviews/{reviewId}/review")
    ExpenseReviewService.ReviewView review(CurrentUser user, @PathVariable long reviewId,
                                           @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch) {
        return service.complete(user, reviewId, ifMatch);
    }

    record CheckRequest(Boolean checked) {
    }

    /** 체크리스트 체크/해제 { checked } (회장·부장급, If-Match 필수, 검토 완료 뒤에는 409) */
    @PatchMapping("/api/expense-review-items/{itemId}")
    ExpenseReviewService.ChecklistItemView check(CurrentUser user, @PathVariable long itemId,
                                                  @RequestHeader(value = IfMatch.HEADER, required = false) String ifMatch,
                                                  @RequestBody CheckRequest request) {
        return service.setChecked(user, itemId, ifMatch, request == null ? null : request.checked());
    }
}
