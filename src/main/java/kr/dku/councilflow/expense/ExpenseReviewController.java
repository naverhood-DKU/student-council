package kr.dku.councilflow.expense;

import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 지출 절차 검토: 요청하면 확정된 규정에서 후보 조항을 찾아 바로 돌려준다. */
@RestController
class ExpenseReviewController {

    private final ExpenseReviewService service;

    ExpenseReviewController(ExpenseReviewService service) {
        this.service = service;
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
}
