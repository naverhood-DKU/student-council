package kr.dku.councilflow.expense;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.ExpenseProposal;
import kr.dku.councilflow.rule.Clause;
import kr.dku.councilflow.rule.ClauseRepository;
import kr.dku.councilflow.rule.RuleDocStatus;
import kr.dku.councilflow.rule.RuleDocument;
import kr.dku.councilflow.rule.RuleDocumentRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지출 절차 검토 요청과 후보 조항 찾기.
 *
 * 요청하면 그 그룹이 확정한 규정에서 관련 조항을 FULLTEXT 로 찾아 후보로 저장한다. 이 단계에서 AI 는 쓰지 않는다.
 * - 조항 원문은 DB 의 검수된 Clause.text 를 그대로 돌려준다(원칙 4).
 * - 후보를 못 찾으면 NEEDS_CHECK 이다. "문제없음" 상태는 없다(원칙 5).
 * - 요청은 부장급 이상(회장·부장)이 할 수 있고 부원은 볼 수만 있다. 서비스 관리자는 읽기 전용이다.
 *   (재정부 부장이 어디까지 할지는 정해지는 대로 조정한다.)
 */
@Service
public class ExpenseReviewService {

    static final int MAX_CANDIDATES = 10;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ExpenseReviewRepository reviews;
    private final ClauseRepository clauses;
    private final RuleDocumentRepository docs;
    private final DecisionRepository decisions;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final Clock clock;

    ExpenseReviewService(ExpenseReviewRepository reviews, ClauseRepository clauses, RuleDocumentRepository docs,
                         DecisionRepository decisions, OrgAccessGuard accessGuard, AuditService audit, Clock clock) {
        this.reviews = reviews;
        this.clauses = clauses;
        this.docs = docs;
        this.decisions = decisions;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.clock = clock;
    }

    /** 요청. 결정(decisionId)을 주면 비어 있는 항목·금액·재원은 그 결정의 지출 언급으로 채운다. */
    public record CreateRequest(Long orgId, Long decisionId, String item, Long amount, String fundSource,
                                LocalDate plannedDate, String note, String keywords) {
    }

    public record CandidateView(long clauseId, int rank, String label, String title, String text, long docId,
                                String docTitle, String docVersion, LocalDate docEffectiveDate, RuleDocStatus docStatus) {
    }

    public record ReviewView(long id, long orgId, Long decisionId, String item, Long amount, String fundSource,
                             LocalDate plannedDate, String note, String searchText, ExpenseReviewStatus status,
                             List<CandidateView> candidates, List<String> notices, Instant createdAt, long version) {
    }

    public record ReviewSummary(long id, String item, Long amount, ExpenseReviewStatus status, int candidateCount,
                                Instant createdAt) {
    }

    @Transactional
    public ReviewView create(CurrentUser user, CreateRequest request) {
        if (request == null || request.orgId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "그룹을 알 수 없습니다.");
        }
        long orgId = request.orgId();
        OrgAccess access = accessGuard.requireMember(user, orgId);
        if (access.adminView() || access.role() == MembershipRole.VIEWER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "회장이나 부장급 직책만 지출 검토를 요청할 수 있습니다.");
        }

        String item = request.item();
        Long amount = request.amount();
        String fundSource = request.fundSource();
        Long decisionId = request.decisionId();
        if (decisionId != null) {
            Decision decision = decisions.findById(decisionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            if (decision.getMeeting().getOrganization().getId() != orgId) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            if (decision.getStatus() != DecisionStatus.APPROVED) {
                throw new ApiException(ErrorCode.INVALID_TRANSITION, "승인된 결정에서만 지출 검토를 요청할 수 있습니다.");
            }
            ExpenseProposal proposal = decision.getExpenseProposal();
            if (proposal != null) {
                item = blank(item) ? proposal.item() : item;
                amount = amount == null ? proposal.amount() : amount;
                fundSource = blank(fundSource) ? proposal.fundSource() : fundSource;
            }
        }
        item = required(item, "지출 항목", 200);
        fundSource = optional(fundSource, "재원", 100);
        String note = optional(request.note(), "설명", 1000);
        String keywords = optional(request.keywords(), "찾을 말", 200);
        if (amount != null && (amount < 0 || amount > 10_000_000_000_000L)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "금액을 확인해 주세요.");
        }

        String query = SearchTerms.build(item, fundSource, note, keywords);
        if (query == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지출 항목에 두 글자 이상의 낱말이 있어야 규정에서 찾을 수 있습니다.");
        }
        LocalDate asOf = request.plannedDate() != null ? request.plannedDate() : LocalDate.now(clock.withZone(SEOUL));
        List<Clause> found = clauses.searchConfirmed(orgId, query, asOf, MAX_CANDIDATES);
        ExpenseReviewStatus status = found.isEmpty() ? ExpenseReviewStatus.NEEDS_CHECK : ExpenseReviewStatus.CANDIDATES_FOUND;

        ExpenseReview review = reviews.save(new ExpenseReview(orgId, decisionId, item, amount, fundSource,
                request.plannedDate(), note, query, status, found.stream().map(Clause::getId).toList(), access.userId()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("item", item);
        after.put("amount", amount);
        after.put("decisionId", decisionId);
        after.put("status", status.name());
        after.put("candidateClauseIds", review.getClauseIds());
        audit.record(access.userId(), orgId, "EXPENSE_REVIEW_REQUESTED", "EXPENSE_REVIEW", review.getId(), null, after);
        return view(review);
    }

    @Transactional(readOnly = true)
    public List<ReviewSummary> list(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return reviews.findByOrgIdOrderByIdDesc(orgId).stream()
                .map(r -> new ReviewSummary(r.getId(), r.getItem(), r.getAmount(), r.getStatus(),
                        r.getClauseIds().size(), r.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewView get(CurrentUser user, long reviewId) {
        ExpenseReview review = reviews.findById(reviewId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        accessGuard.requireMember(user, review.getOrgId());
        return view(review);
    }

    // ---- 내부 ----

    private ReviewView view(ExpenseReview review) {
        List<Long> ids = review.getClauseIds();
        Map<Long, Clause> byId = new HashMap<>();
        clauses.findAllById(ids).forEach(c -> byId.put(c.getId(), c));
        Map<Long, RuleDocument> docById = new HashMap<>();
        docs.findAllById(byId.values().stream().map(Clause::getDocId).distinct().toList())
                .forEach(d -> docById.put(d.getId(), d));

        List<CandidateView> candidates = new ArrayList<>();
        int rank = 0;
        for (Long id : ids) {
            Clause c = byId.get(id);
            RuleDocument d = c == null ? null : docById.get(c.getDocId());
            if (c == null || d == null) {
                continue;
            }
            candidates.add(new CandidateView(c.getId(), ++rank, c.getLabel(), c.getTitle(), c.getText(), d.getId(),
                    d.getTitle(), d.getDocVersion(), d.getEffectiveDate(), d.getStatus()));
        }
        return new ReviewView(review.getId(), review.getOrgId(), review.getDecisionId(), review.getItem(),
                review.getAmount(), review.getFundSource(), review.getPlannedDate(), review.getNote(),
                review.getSearchText(), review.getStatus(), candidates, notices(review, candidates),
                review.getCreatedAt(), review.getVersion());
    }

    /** 사람이 결과를 읽을 때 놓치면 안 되는 점. 저장하지 않고 볼 때마다 계산한다. */
    private List<String> notices(ExpenseReview review, List<CandidateView> candidates) {
        List<String> notices = new ArrayList<>();
        if (docs.countByOrgIdAndStatus(review.getOrgId(), RuleDocStatus.CONFIRMED) == 0) {
            notices.add("이 그룹에 확정된 규정이 없습니다. 규정을 올려 검수·확정해야 근거를 찾을 수 있습니다.");
        } else if (candidates.isEmpty()) {
            notices.add("관련 조항을 찾지 못했습니다. 이것은 문제가 없다는 뜻이 아닙니다. 규정에서 이 지출을 다루는지 직접 확인해 주세요.");
        }
        if (review.getAmount() == null) {
            notices.add("금액이 없어 금액 기준 승인 절차를 가릴 수 없습니다.");
        }
        if (review.getPlannedDate() == null) {
            notices.add("집행 예정일이 없어 요청한 날 시행 중인 규정에서 찾았습니다.");
        }
        if (candidates.stream().anyMatch(c -> c.docStatus() != RuleDocStatus.CONFIRMED)) {
            notices.add("찾은 뒤에 폐기된 규정의 조항이 포함돼 있습니다. 현행 규정인지 확인해 주세요.");
        }
        long titles = candidates.stream().map(CandidateView::docTitle).distinct().count();
        long docIds = candidates.stream().map(CandidateView::docId).distinct().count();
        if (docIds > titles) {
            notices.add("같은 이름의 규정이 여러 버전으로 확정돼 있습니다. 옛 버전은 폐기해 주세요.");
        }
        return notices;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String required(String value, String field, int max) {
        if (blank(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + "을(를) 입력해 주세요.");
        }
        return optional(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        if (blank(value)) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + "은(는) " + max + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }
}
