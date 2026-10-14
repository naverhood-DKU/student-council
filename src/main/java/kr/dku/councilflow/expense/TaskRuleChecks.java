package kr.dku.councilflow.expense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.decision.Task;
import kr.dku.councilflow.decision.TaskRepository;
import kr.dku.councilflow.job.JobRepository;
import kr.dku.councilflow.job.JobStatus;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.rule.Clause;
import kr.dku.councilflow.rule.ClauseRepository;
import kr.dku.councilflow.rule.RuleDocument;
import kr.dku.councilflow.rule.RuleDocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 카드에 붙이는 "규정 확인" 표시를 만든다. 지출 검토(회의 분석 뒤 자동으로 만든 것)의 서버 검증을 통과한 결과만 쓴다.
 *
 * - 배지는 확정이 아니라 가능성이다("금지 가능성"·"위험"). 근거 조항과 원문에서 확인된 인용 구절을 항상 같이 준다.
 * - 조항 원문은 여기서 만들지 않는다. 카드의 "관련 조항 보기"가 지출 검토 화면(reviewId)으로 이어져 DB 원문을 보여 준다.
 * - 규정 확인 대상이 아닌 카드(돈과 관련 없는 카드)는 지출 검토가 없어 표시가 없다(null).
 */
@Component
public class TaskRuleChecks {

    /** 배지의 근거: 서버 검증을 통과한 금지·제한·충돌 내용과 조항 이름·인용 구절 */
    public record Evidence(String kind, String text, List<ExpenseReviewService.ClauseRef> clauses, String quote) {
    }

    public record RuleCheckView(long reviewId, RuleBadge badge, String label, String summary, int candidateCount,
                                List<Evidence> evidence) {
    }

    private final ExpenseReviewRepository reviews;
    private final ClauseRepository clauses;
    private final RuleDocumentRepository docs;
    private final JobRepository jobs;
    private final TaskRepository tasks;
    private final OrgAccessGuard accessGuard;

    TaskRuleChecks(ExpenseReviewRepository reviews, ClauseRepository clauses, RuleDocumentRepository docs,
                   JobRepository jobs, TaskRepository tasks, OrgAccessGuard accessGuard) {
        this.reviews = reviews;
        this.clauses = clauses;
        this.docs = docs;
        this.jobs = jobs;
        this.tasks = tasks;
        this.accessGuard = accessGuard;
    }

    /** 업무 카드(task id)마다 규정 확인 표시. 확인 대상이 아니었던 카드는 맵에 없다. */
    @Transactional(readOnly = true)
    public Map<Long, RuleCheckView> byTask(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        // id 오름차순으로 받아 같은 카드의 마지막(가장 최근) 검토가 남게 한다.
        Map<Long, ExpenseReview> latest = new HashMap<>();
        for (ExpenseReview review : reviews.findByTaskIdInOrderByIdAsc(taskIds)) {
            latest.put(review.getTaskId(), review);
        }
        if (latest.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExpenseReviewService.ClauseRef> refs = refsOf(latest.values());
        Map<Long, RuleCheckView> result = new LinkedHashMap<>();
        latest.forEach((taskId, review) -> result.put(taskId, view(review, refs)));
        return result;
    }

    /** 카드 하나의 규정 확인 표시. 카드를 볼 수 있는 그룹 구성원만. 대상이 아니면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<RuleCheckView> get(CurrentUser user, long taskId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        accessGuard.requireMember(user, task.getDecision().getMeeting().getOrgId());
        return Optional.ofNullable(byTask(List.of(taskId)).get(taskId));
    }

    private RuleCheckView view(ExpenseReview review, Map<Long, ExpenseReviewService.ClauseRef> refs) {
        GuidanceResult ai = review.getAiResult();
        boolean noCandidate = review.getClauseIds().isEmpty();
        boolean jobFailed = ai == null && !noCandidate && jobs
                .findFirstByTypeAndTargetIdOrderByIdDesc(JobType.EXPENSE, review.getId())
                .map(job -> job.getStatus() == JobStatus.FAILED || job.getStatus() == JobStatus.FAILED_UNKNOWN)
                .orElse(false);
        RuleBadge badge = RuleBadge.of(ai, noCandidate, jobFailed);

        List<Evidence> evidence = new ArrayList<>();
        if (ai != null) {
            for (GuidanceResult.Restriction r : ai.restrictionsOrEmpty()) {
                if (r.relatedOrTrue()) {
                    evidence.add(new Evidence(r.kind(), r.text(), refsOf(r.clauseIds(), refs), r.quote()));
                }
            }
            if (ai.conflicts() != null) {
                for (GuidanceResult.Conflict c : ai.conflicts()) {
                    evidence.add(new Evidence("CONFLICT", c.description(), refsOf(c.clauseIds(), refs), null));
                }
            }
        }
        return new RuleCheckView(review.getId(), badge, badge.label(), summary(badge), review.getClauseIds().size(),
                evidence);
    }

    /** 배지 옆에 보이는 한 줄 설명. 확정이 아님을 항상 알린다. */
    static String summary(RuleBadge badge) {
        return switch (badge) {
            case PROHIBITED -> "이 업무와 직접 관련 있어 보이는 금지 조항이 있습니다. 확정이 아니니 조항 원문을 확인해 주세요.";
            case RISK -> "규정이 제한하거나 서로 다르게 말하는 부분이 있어 보입니다. 확정이 아니니 조항 원문을 확인해 주세요.";
            case NONE -> "서버가 확인한 금지·위험은 없지만 안전하다는 뜻은 아닙니다. 관련 조항을 확인해 주세요.";
            case UNCHECKED -> "관련 조항을 찾지 못했거나 확인된 것이 없습니다. 문제가 없다는 뜻이 아닙니다.";
            case PENDING -> "규정에서 관련 조항을 확인하는 중입니다.";
            case FAILED -> "AI 안내를 받지 못했습니다. 후보 조항 원문은 볼 수 있습니다.";
        };
    }

    private static List<ExpenseReviewService.ClauseRef> refsOf(List<Long> ids,
                                                                Map<Long, ExpenseReviewService.ClauseRef> refs) {
        List<ExpenseReviewService.ClauseRef> result = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                ExpenseReviewService.ClauseRef ref = refs.get(id);
                if (ref != null) {
                    result.add(ref);
                }
            }
        }
        return result;
    }

    /** 여러 검토가 가리키는 조항 이름을 한 번에 읽는다. */
    private Map<Long, ExpenseReviewService.ClauseRef> refsOf(Collection<ExpenseReview> list) {
        Set<Long> ids = new HashSet<>();
        for (ExpenseReview review : list) {
            GuidanceResult ai = review.getAiResult();
            if (ai == null) {
                continue;
            }
            ai.restrictionsOrEmpty().forEach(r -> ids.addAll(r.clauseIds() == null ? List.of() : r.clauseIds()));
            if (ai.conflicts() != null) {
                ai.conflicts().forEach(c -> ids.addAll(c.clauseIds() == null ? List.of() : c.clauseIds()));
            }
        }
        Map<Long, ExpenseReviewService.ClauseRef> refs = new HashMap<>();
        if (ids.isEmpty()) {
            return refs;
        }
        Map<Long, Clause> byId = new HashMap<>();
        clauses.findAllById(ids).forEach(c -> byId.put(c.getId(), c));
        Map<Long, RuleDocument> docById = new HashMap<>();
        docs.findAllById(byId.values().stream().map(Clause::getDocId).distinct().toList())
                .forEach(d -> docById.put(d.getId(), d));
        byId.values().forEach(c -> {
            RuleDocument d = docById.get(c.getDocId());
            if (d != null) {
                refs.put(c.getId(), new ExpenseReviewService.ClauseRef(c.getId(), c.getLabel(), d.getTitle(),
                        RestrictionWords.mentionsRestriction(c.getText())));
            }
        });
        return refs;
    }
}
