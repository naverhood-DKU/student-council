package kr.dku.councilflow.expense;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccess;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.ExpenseProposal;
import kr.dku.councilflow.job.Job;
import kr.dku.councilflow.job.JobRepository;
import kr.dku.councilflow.job.JobStatus;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.job.JobView;
import kr.dku.councilflow.rule.Clause;
import kr.dku.councilflow.rule.ClauseRepository;
import kr.dku.councilflow.rule.RuleDocStatus;
import kr.dku.councilflow.rule.RuleDocument;
import kr.dku.councilflow.rule.RuleDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지출 절차 검토: 후보 조항 찾기(AI 없이) → AI 절차 안내(작업 큐) → 사람의 검토 완료.
 *
 * - 조항 원문은 DB 의 검수된 Clause.text 를 그대로 돌려준다. AI 는 조항 이름과 안내문만 낸다(원칙 4).
 * - 후보를 못 찾거나 서버 검증을 통과한 안내가 없으면 NEEDS_CHECK 이다. "문제없음" 상태는 없다(원칙 5).
 * - 후보가 있을 때만 EXPENSE 작업을 만든다. 후보가 없으면 AI 를 부르지 않는다.
 * - 권한은 ExpenseReviewPolicy 에 모아 두었다. 서비스 관리자는 읽기 전용이다.
 */
@Service
public class ExpenseReviewService {

    static final int MAX_CANDIDATES = 10;
    /** 금액 표기("30만원")로만 걸려 덧붙는 후보의 최대 개수. "만원" 같은 글자 조각 때문에 잡음이 늘지 않게 한다. */
    static final int MAX_AMOUNT_CANDIDATES = 3;
    private static final int SEARCH_TEXT_MAX = 600;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 화면과 API 가 항상 같이 보여 주는 고정 안내문. 모델이 쓰지 않는다. */
    static final String DISCLAIMER = "이 안내는 감사를 대체하지 않습니다. 조항 원문을 직접 확인하고, "
            + "적용 여부와 필요한 절차는 사람이 판단해 주세요.";

    private final ExpenseReviewRepository reviews;
    private final ExpenseChecklistItemRepository checklist;
    private final ClauseRepository clauses;
    private final RuleDocumentRepository docs;
    private final DecisionRepository decisions;
    private final JobRepository jobs;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final Clock clock;

    ExpenseReviewService(ExpenseReviewRepository reviews, ExpenseChecklistItemRepository checklist,
                         ClauseRepository clauses, RuleDocumentRepository docs, DecisionRepository decisions,
                         JobRepository jobs, OrgAccessGuard accessGuard, AuditService audit, Clock clock) {
        this.reviews = reviews;
        this.checklist = checklist;
        this.clauses = clauses;
        this.docs = docs;
        this.decisions = decisions;
        this.jobs = jobs;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.clock = clock;
    }

    /** 요청. 결정(decisionId)을 주면 비어 있는 항목·금액·재원은 그 결정의 지출 언급으로 채운다. */
    public record CreateRequest(Long orgId, Long decisionId, String item, Long amount, String fundSource,
                                LocalDate plannedDate, String note, String keywords) {
    }

    /**
     * matchedParagraphs 는 조항 원문에서 지출 항목과 글자가 겹치는 문단(원문을 자른 것, 최대 3개)이다.
     * 모델이 긴 조항의 특정 문단을 놓쳐도 사람이 바로 볼 수 있게 한다. 말이 달라 글자가 겹치지 않으면 비어 있다.
     */
    public record CandidateView(long clauseId, int rank, String label, String title, String text, long docId,
                                String docTitle, String docVersion, LocalDate docEffectiveDate, RuleDocStatus docStatus,
                                boolean mentionsRestriction, List<String> matchedParagraphs) {
    }

    /**
     * 화면에서 근거 조항으로 연결하기 위한 짧은 참조.
     * mentionsRestriction 은 그 조항 글에 금지·제한 표현이 있다는 뜻이다(서버의 낱말 검사). 승인·증빙 항목이 이런 조항을
     * 근거로 들면 화면이 "원문을 확인하라"고 알린다.
     */
    public record ClauseRef(long clauseId, String label, String docTitle, boolean mentionsRestriction) {
    }

    /** 사용을 금지(PROHIBITED)하거나 한도·조건을 두는(LIMITED) 조항 내용. quote 는 원문에서 확인된 구절 */
    public record RestrictionView(String kind, String text, List<ClauseRef> clauses, String quote, boolean related) {
    }

    public record ChecklistItemView(long id, ChecklistKind kind, String text, List<ClauseRef> clauses, String quote,
                                    boolean checked, Instant checkedAt, long version) {
    }

    public record ConflictView(String description, List<ClauseRef> clauses) {
    }

    /** AI 재확인에서 이 지출에 해당하지 않거나 인용과 맞지 않는다고 본 승인(APPROVAL)·증빙(EVIDENCE). 체크리스트 밖에 따로 보인다. */
    public record SetAsideView(String kind, String text, List<ClauseRef> clauses, String quote, String reason) {
    }

    /** 서버 검증을 통과한 AI 안내. note 는 조항으로 확인된 내용이 아니라 참고용이다. */
    public record GuidanceView(String outcome, List<RestrictionView> restrictions, List<ConflictView> conflicts,
                               List<SetAsideView> setAside, List<String> missingFacts, String note, int droppedCount,
                               List<String> droppedNotes, String generatedAt) {
    }

    public record ReviewView(long id, long orgId, Long decisionId, String item, Long amount, String fundSource,
                             LocalDate plannedDate, String note, String searchText, ExpenseReviewStatus status,
                             List<CandidateView> candidates, List<String> notices, GuidanceView guidance,
                             List<ChecklistItemView> checklist, JobView job, String disclaimer, Instant reviewedAt,
                             Instant createdAt, long version) {
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
        if (!ExpenseReviewPolicy.canRequestOrWork(access)) {
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
        Found candidates = findCandidates(orgId, query, amount, asOf);
        List<Clause> found = candidates.clauses();
        String searchText = candidates.searchText();
        ExpenseReviewStatus status = found.isEmpty() ? ExpenseReviewStatus.NEEDS_CHECK : ExpenseReviewStatus.CANDIDATES_FOUND;

        ExpenseReview review = reviews.save(new ExpenseReview(orgId, decisionId, item, amount, fundSource,
                request.plannedDate(), note, searchText, status, found.stream().map(Clause::getId).toList(), access.userId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("item", item);
        after.put("amount", amount);
        after.put("decisionId", decisionId);
        after.put("status", status.name());
        after.put("candidateClauseIds", review.getClauseIds());
        if (status == ExpenseReviewStatus.CANDIDATES_FOUND) {
            // 후보가 있을 때만 AI 안내 작업을 만든다. 후보가 없으면 AI 를 부르지 않는다.
            Job job = jobs.save(Job.queue(JobType.EXPENSE, review.getId()));
            after.put("jobId", job.getId());
        }
        audit.record(access.userId(), orgId, "EXPENSE_REVIEW_REQUESTED", "EXPENSE_REVIEW", review.getId(), null, after);
        return view(review);
    }

    /** 후보 조항과, 실제로 찾는 데 쓴 말 */
    private record Found(List<Clause> clauses, String searchText) {
    }

    /**
     * 후보 조항 찾기(AI 없음). 그 그룹이 올린 확정 규정과 모든 그룹이 쓰는 공통 규정에서 찾는다.
     * 금액이 있으면 "30만원"처럼 규정에 쓰인 표기로도 찾되, 항목으로 찾은 조항 뒤에만 덧붙여서
     * 금액만 언급한 조항이 항목에 맞는 조항보다 위로 올라오지 않게 한다.
     */
    private Found findCandidates(long orgId, String query, Long amount, LocalDate asOf) {
        List<Clause> found = new ArrayList<>(clauses.searchConfirmed(orgId, query, asOf, MAX_CANDIDATES));
        String searchText = query;
        String amountQuery = SearchTerms.amountTerms(amount);
        if (amountQuery != null && found.size() < MAX_CANDIDATES) {
            Set<Long> seen = new LinkedHashSet<>(found.stream().map(Clause::getId).toList());
            int appended = 0;
            for (Clause clause : clauses.searchConfirmed(orgId, amountQuery, asOf, MAX_CANDIDATES)) {
                if (found.size() < MAX_CANDIDATES && appended < MAX_AMOUNT_CANDIDATES && seen.add(clause.getId())) {
                    found.add(clause);
                    appended++;
                }
            }
            searchText = truncate(query + " " + amountQuery, SEARCH_TEXT_MAX);
        }
        return new Found(found, searchText);
    }

    /**
     * 회의 분석이 끝난 뒤 업무 카드 하나에 대해 서버가 자동으로 하는 규정 확인.
     * 사람의 요청이 아니므로 권한 확인을 거치지 않고(요청자 없음), 후보가 있을 때만 AI 안내 작업을 만든다.
     * 지출 항목에서 두 글자 이상의 낱말을 못 뽑으면 만들지 않고 null 을 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    ExpenseReview createForTask(long orgId, Long decisionId, long taskId, String item, Long amount,
                                String fundSource, String note) {
        String query = SearchTerms.build(item, fundSource, note, null);
        if (query == null) {
            return null;
        }
        LocalDate asOf = LocalDate.now(clock.withZone(SEOUL));
        Found candidates = findCandidates(orgId, query, amount, asOf);
        ExpenseReviewStatus status = candidates.clauses().isEmpty()
                ? ExpenseReviewStatus.NEEDS_CHECK : ExpenseReviewStatus.CANDIDATES_FOUND;
        ExpenseReview review = reviews.save(ExpenseReview.auto(orgId, decisionId, taskId, truncate(item, 200), amount,
                fundSource, note, candidates.searchText(), status,
                candidates.clauses().stream().map(Clause::getId).toList()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("taskId", taskId);
        after.put("status", status.name());
        after.put("candidateClauseIds", review.getClauseIds());
        if (status == ExpenseReviewStatus.CANDIDATES_FOUND) {
            Job job = jobs.save(Job.queue(JobType.EXPENSE, review.getId()));
            after.put("jobId", job.getId());
        }
        audit.record(null, orgId, "EXPENSE_REVIEW_AUTO_CREATED", "EXPENSE_REVIEW", review.getId(), null, after);
        return review;
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
        ExpenseReview review = load(reviewId);
        accessGuard.requireMember(user, review.getOrgId());
        return view(review);
    }

    /**
     * AI 안내 다시 받기. 이미 대기·진행 중인 작업이 있으면 그 작업을 돌려주고 새로 만들지 않는다.
     * 검토 완료된 건과 후보 조항이 없는 건은 409.
     */
    @Transactional
    public JobView requestGuidance(CurrentUser user, long reviewId) {
        ExpenseReview review = load(reviewId);
        OrgAccess access = accessGuard.requireMember(user, review.getOrgId());
        if (!ExpenseReviewPolicy.canRequestOrWork(access)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "회장이나 부장급 직책만 AI 안내를 다시 받을 수 있습니다.");
        }
        if (review.getStatus() == ExpenseReviewStatus.REVIEWED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "검토 완료한 건은 AI 안내를 다시 받을 수 없습니다.");
        }
        if (review.getClauseIds().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "후보 조항이 없어 AI 안내를 받을 수 없습니다. 규정을 확정하거나 찾을 말을 바꿔 새로 요청해 주세요.");
        }
        var active = jobs.findFirstByTypeAndTargetIdOrderByIdDesc(JobType.EXPENSE, reviewId)
                .filter(job -> EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING).contains(job.getStatus()));
        if (active.isPresent()) {
            return JobView.of(active.get());
        }
        Job job = jobs.save(Job.queue(JobType.EXPENSE, reviewId));
        audit.record(access.userId(), review.getOrgId(), "EXPENSE_GUIDANCE_REQUESTED", "EXPENSE_REVIEW", reviewId,
                null, Map.of("jobId", job.getId()));
        return JobView.of(job);
    }

    /**
     * 검토 완료. "규정에 맞다"가 아니라 "사람이 AI 안내와 조항 원문을 확인했다"는 표시다.
     * AI 안내가 끝난 뒤에만, 한 번만 할 수 있다.
     */
    @Transactional
    public ReviewView complete(CurrentUser user, long reviewId, String ifMatch) {
        ExpenseReview review = load(reviewId);
        OrgAccess access = accessGuard.requireMember(user, review.getOrgId());
        if (!ExpenseReviewPolicy.canComplete(access)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "그룹 회장만 검토 완료를 표시할 수 있습니다.");
        }
        IfMatch.check(ifMatch, review.getVersion());
        if (review.getStatus() == ExpenseReviewStatus.REVIEWED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "이미 검토 완료한 건입니다.");
        }
        if (review.getAiResult() == null || review.getStatus() == ExpenseReviewStatus.CANDIDATES_FOUND) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "AI 안내가 끝난 뒤에 검토 완료를 표시할 수 있습니다.");
        }
        ExpenseReviewStatus before = review.getStatus();
        review.markReviewed(access.userId(), clock.instant());
        audit.record(access.userId(), review.getOrgId(), "EXPENSE_REVIEW_COMPLETED", "EXPENSE_REVIEW", reviewId,
                Map.of("status", before.name()), Map.of("status", ExpenseReviewStatus.REVIEWED.name()));
        reviews.flush();
        return view(review);
    }

    /** 체크리스트 항목 체크/해제. 검토 완료 뒤에는 잠긴다. */
    @Transactional
    public ChecklistItemView setChecked(CurrentUser user, long itemId, String ifMatch, Boolean checked) {
        ExpenseChecklistItem item = checklist.findById(itemId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        ExpenseReview review = load(item.getReviewId());
        OrgAccess access = accessGuard.requireMember(user, review.getOrgId());
        if (!ExpenseReviewPolicy.canRequestOrWork(access)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "회장이나 부장급 직책만 체크리스트를 바꿀 수 있습니다.");
        }
        IfMatch.check(ifMatch, item.getVersion());
        if (checked == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "checked 값을 보내 주세요.");
        }
        if (review.getStatus() == ExpenseReviewStatus.REVIEWED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "검토 완료한 건의 체크리스트는 바꿀 수 없습니다.");
        }
        boolean before = item.isChecked();
        if (before != checked) {
            item.setChecked(checked, access.userId(), clock.instant());
            audit.record(access.userId(), review.getOrgId(), "EXPENSE_CHECKLIST_CHANGED", "EXPENSE_CHECKLIST_ITEM",
                    itemId, Map.of("checked", before), Map.of("checked", checked));
            checklist.flush();
        }
        return checklistView(item, clauseRefs(review));
    }

    // ---- 내부 ----

    private ExpenseReview load(long reviewId) {
        return reviews.findById(reviewId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private ReviewView view(ExpenseReview review) {
        List<Long> ids = review.getClauseIds();
        Map<Long, Clause> byId = new HashMap<>();
        clauses.findAllById(ids).forEach(c -> byId.put(c.getId(), c));
        Map<Long, RuleDocument> docById = new HashMap<>();
        docs.findAllById(byId.values().stream().map(Clause::getDocId).distinct().toList())
                .forEach(d -> docById.put(d.getId(), d));

        List<CandidateView> candidates = new ArrayList<>();
        Map<Long, ClauseRef> refs = new HashMap<>();
        int rank = 0;
        for (Long id : ids) {
            Clause c = byId.get(id);
            RuleDocument d = c == null ? null : docById.get(c.getDocId());
            if (c == null || d == null) {
                continue;
            }
            boolean restrictive = RestrictionWords.mentionsRestriction(c.getText());
            candidates.add(new CandidateView(c.getId(), ++rank, c.getLabel(), c.getTitle(), c.getText(), d.getId(),
                    d.getTitle(), d.getDocVersion(), d.getEffectiveDate(), d.getStatus(), restrictive,
                    ClauseParagraphs.overlapping(c.getText(),
                            review.getSearchText() != null ? review.getSearchText() : review.getItem())));
            refs.put(c.getId(), new ClauseRef(c.getId(), c.getLabel(), d.getTitle(), restrictive));
        }

        GuidanceView guidance = null;
        GuidanceResult ai = review.getAiResult();
        if (ai != null) {
            List<ConflictView> conflicts = ai.conflicts() == null ? List.of() : ai.conflicts().stream()
                    .map(c -> new ConflictView(c.description(), refsOf(c.clauseIds(), refs)))
                    .toList();
            List<RestrictionView> restrictions = ai.restrictionsOrEmpty().stream()
                    .map(r -> new RestrictionView(r.kind(), r.text(), refsOf(r.clauseIds(), refs), r.quote(), r.relatedOrTrue()))
                    .toList();
            List<SetAsideView> setAside = ai.setAsideOrEmpty().stream()
                    .map(s -> new SetAsideView(s.kind(), s.text(), refsOf(s.clauseIds(), refs), s.quote(), s.reason()))
                    .toList();
            guidance = new GuidanceView(ai.outcome(), restrictions, conflicts, setAside, review.getMissingFacts(),
                    review.getGuidance(), ai.droppedCount(),
                    ai.droppedNotes() == null ? List.of() : ai.droppedNotes(), ai.generatedAt());
        }
        List<ChecklistItemView> items = checklist.findByReviewIdOrderBySeq(review.getId()).stream()
                .map(i -> checklistView(i, refs))
                .toList();
        JobView job = jobs.findFirstByTypeAndTargetIdOrderByIdDesc(JobType.EXPENSE, review.getId())
                .map(JobView::of).orElse(null);
        return new ReviewView(review.getId(), review.getOrgId(), review.getDecisionId(), review.getItem(),
                review.getAmount(), review.getFundSource(), review.getPlannedDate(), review.getNote(),
                review.getSearchText(), review.getStatus(), candidates, notices(review, candidates), guidance, items,
                job, DISCLAIMER, review.getReviewedAt(), review.getCreatedAt(), review.getVersion());
    }

    private Map<Long, ClauseRef> clauseRefs(ExpenseReview review) {
        Map<Long, ClauseRef> refs = new HashMap<>();
        Map<Long, Clause> byId = new HashMap<>();
        clauses.findAllById(review.getClauseIds()).forEach(c -> byId.put(c.getId(), c));
        Map<Long, RuleDocument> docById = new HashMap<>();
        docs.findAllById(byId.values().stream().map(Clause::getDocId).distinct().toList())
                .forEach(d -> docById.put(d.getId(), d));
        byId.values().forEach(c -> {
            RuleDocument d = docById.get(c.getDocId());
            if (d != null) {
                refs.put(c.getId(), new ClauseRef(c.getId(), c.getLabel(), d.getTitle(),
                        RestrictionWords.mentionsRestriction(c.getText())));
            }
        });
        return refs;
    }

    private static ChecklistItemView checklistView(ExpenseChecklistItem item, Map<Long, ClauseRef> refs) {
        return new ChecklistItemView(item.getId(), item.getKind(), item.getText(), refsOf(item.getClauseIds(), refs),
                item.getQuote(), item.isChecked(), item.getCheckedAt(), item.getVersion());
    }

    private static List<ClauseRef> refsOf(List<Long> clauseIds, Map<Long, ClauseRef> refs) {
        List<ClauseRef> result = new ArrayList<>();
        for (Long id : clauseIds) {
            ClauseRef ref = refs.get(id);
            if (ref != null) {
                result.add(ref);
            }
        }
        return result;
    }

    /** 사람이 결과를 읽을 때 놓치면 안 되는 점. 저장하지 않고 볼 때마다 계산한다. */
    private List<String> notices(ExpenseReview review, List<CandidateView> candidates) {
        List<String> notices = new ArrayList<>();
        if (docs.countByOrgIdAndStatus(review.getOrgId(), RuleDocStatus.CONFIRMED) == 0
                && docs.countByOrgIdIsNullAndStatus(RuleDocStatus.CONFIRMED) == 0) {
            notices.add("확정된 규정이 없습니다. 규정이 확정돼 있어야 근거를 찾을 수 있습니다.");
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
        GuidanceResult ai = review.getAiResult();
        if (ai != null && ai.droppedCount() > 0) {
            notices.add("AI 안내 중 서버 검증을 통과하지 못한 " + ai.droppedCount() + "개 항목을 제외했습니다. "
                    + "빠진 내용이 있을 수 있으니 조항 원문을 직접 확인해 주세요.");
        }
        if (ai != null && ExpenseReviewStatus.POSSIBLY_RESTRICTED.name().equals(ai.outcome())) {
            notices.add("후보 조항 중 이 지출의 사용을 금지하거나 제한하는 내용이 있을 수 있습니다. 규정에 따를 절차가 있다는 뜻이 아니니 "
                    + "조항 원문을 직접 확인해 주세요.");
        }
        if (ai != null && ExpenseReviewStatus.NEEDS_CHECK.name().equals(ai.outcome())) {
            notices.add("AI 안내에서 조항으로 확인된 승인·증빙 항목이 없습니다. 이것은 문제가 없다는 뜻이 아닙니다. "
                    + "후보 조항 원문을 직접 확인해 주세요.");
        }
        return notices;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
