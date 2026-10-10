package kr.dku.councilflow.expense;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.job.JobFailure;
import kr.dku.councilflow.job.JobHandler;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.rule.Clause;
import kr.dku.councilflow.rule.ClauseRepository;
import kr.dku.councilflow.rule.RuleDocument;
import kr.dku.councilflow.rule.RuleDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXPENSE 작업: 후보 조항 → AI 절차 안내 → 서버 검증 → 검증을 통과한 것만 저장.
 *
 * - execute 는 트랜잭션 밖에서 AI 를 부르고, 같은 곳에서 의미 검증까지 끝낸다(순수 계산).
 * - onSuccess 는 작업 상태 변경과 같은 트랜잭션에서 저장한다. 사람이 먼저 검토 완료했다면 덮어쓰지 않는다.
 * - 실패하면 상태는 그대로(CANDIDATES_FOUND 등)라서 후보 조항은 계속 보인다. 작업 상태와 오류 종류는 화면에 나온다.
 */
@Component
class ExpenseGuidanceHandler implements JobHandler<ExpenseGuidanceHandler.Result> {

    private static final Logger log = LoggerFactory.getLogger(ExpenseGuidanceHandler.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    record Result(ExpenseGuidanceValidator.Validated validated) {
    }

    /**
     * 모델에 보낼 메시지, 금지 항목의 관련성을 가릴 지출 항목 글(subject), 적용 재확인에 쓸 지출 정보와 조항 글.
     */
    private record Prepared(ExpenseGuidancePrompt.Built built, String subject,
                            ExpenseGuidancePrompt.Subject facts,
                            Map<Long, ExpenseApplicabilityChecker.ClauseText> clauseTexts) {
    }

    private final ExpenseReviewRepository reviews;
    private final ExpenseChecklistItemRepository items;
    private final ClauseRepository clauses;
    private final RuleDocumentRepository docs;
    private final ExpenseGuidancePrompt prompt;
    private final ExpenseGuidanceAnalyzer analyzer;
    private final ExpenseApplicabilityChecker applicability;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    ExpenseGuidanceHandler(ExpenseReviewRepository reviews, ExpenseChecklistItemRepository items,
                           ClauseRepository clauses, RuleDocumentRepository docs, ExpenseGuidancePrompt prompt,
                           ExpenseGuidanceAnalyzer analyzer, ExpenseApplicabilityChecker applicability,
                           AuditService audit, Clock clock, PlatformTransactionManager transactionManager) {
        this.reviews = reviews;
        this.items = items;
        this.clauses = clauses;
        this.docs = docs;
        this.prompt = prompt;
        this.analyzer = analyzer;
        this.applicability = applicability;
        this.audit = audit;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public JobType type() {
        return JobType.EXPENSE;
    }

    @Override
    public Result execute(long reviewId) {
        Prepared prepared = readOnly.execute(status -> build(reviewId));
        ParsedGuidance parsed = analyzer.analyze(prepared.built().messages());
        ExpenseGuidanceValidator.Validated validated =
                ExpenseGuidanceValidator.validate(parsed, prepared.built().candidates(), prepared.subject());
        return new Result(recheck(validated, prepared));
    }

    /**
     * 남은 항목이 이 지출에 실제로 해당하는지 한 번 더 묻는다. 이 호출이 실패해도 작업은 실패하지 않는다.
     * 첫 결과는 이미 서버 검증을 통과했으므로 재확인을 못 했다고만 알린다.
     */
    private ExpenseGuidanceValidator.Validated recheck(ExpenseGuidanceValidator.Validated validated, Prepared prepared) {
        List<ExpenseApplicability.Target> targets = ExpenseApplicability.targets(validated);
        if (!applicability.enabled() || targets.isEmpty()) {
            return validated;
        }
        Map<String, ExpenseApplicability.Verdict> verdicts =
                applicability.check(prepared.facts(), targets, prepared.clauseTexts());
        if (verdicts == null) {
            return validated.withNote("각 항목이 이 지출에 해당하는지 AI로 다시 확인하지 못했습니다. 조항 원문과 비교해 직접 확인해 주세요.");
        }
        return ExpenseApplicability.apply(validated, verdicts);
    }

    @Override
    public void onSuccess(long reviewId, Result result) {
        ExpenseReview review = reviews.findById(reviewId).orElse(null);
        if (review == null) {
            return;
        }
        if (review.getStatus() == ExpenseReviewStatus.REVIEWED) {
            // 사람이 먼저 검토 완료를 눌렀다. AI 결과로 덮어쓰지 않는다.
            log.info("expense guidance discarded: review already completed");
            return;
        }
        ExpenseGuidanceValidator.Validated v = result.validated();

        // 같은 항목이 다시 나오면 사람이 해 둔 체크를 이어 준다.
        Map<String, ExpenseChecklistItem> previous = new HashMap<>();
        for (ExpenseChecklistItem old : items.findByReviewIdOrderBySeq(reviewId)) {
            if (old.isChecked()) {
                previous.put(key(old.getKind(), old.getText()), old);
            }
        }
        items.deleteByReviewId(reviewId);
        items.flush();

        int seq = 0;
        List<ExpenseChecklistItem> created = new ArrayList<>();
        for (ExpenseGuidanceValidator.Item item : v.approvals()) {
            created.add(new ExpenseChecklistItem(reviewId, ++seq, ChecklistKind.APPROVAL, item.text(), item.clauseIds(), item.quote()));
        }
        for (ExpenseGuidanceValidator.Item item : v.evidence()) {
            created.add(new ExpenseChecklistItem(reviewId, ++seq, ChecklistKind.EVIDENCE, item.text(), item.clauseIds(), item.quote()));
        }
        for (ExpenseChecklistItem item : created) {
            ExpenseChecklistItem old = previous.get(key(item.getKind(), item.getText()));
            if (old != null) {
                item.carryOver(old);
            }
        }
        items.saveAll(created);

        GuidanceResult stored = new GuidanceResult(v.status().name(), v.conflicts(), v.restrictions(),
                v.droppedCount(), v.droppedNotes(), clock.instant().toString(), v.setAside());
        review.applyGuidance(v.status(), stored, v.missingFacts(), v.guidance());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", v.status().name());
        after.put("approvals", v.approvals().size());
        after.put("evidence", v.evidence().size());
        after.put("conflicts", v.conflicts().size());
        after.put("restrictions", v.restrictions().size());
        after.put("setAside", v.setAside().size());
        after.put("dropped", v.droppedCount());
        audit.record(null, review.getOrgId(), "EXPENSE_GUIDANCE_GENERATED", "EXPENSE_REVIEW", reviewId, null, after);
    }

    @Override
    public void onFinalFailure(long reviewId) {
        // 상태를 바꾸지 않는다. 후보 조항은 그대로 보이고 작업 상태·오류 종류는 화면에서 job 으로 읽는다.
    }

    private Prepared build(long reviewId) {
        ExpenseReview review = reviews.findById(reviewId).orElseThrow(() -> JobFailure.permanent("REVIEW_NOT_FOUND"));
        if (review.getStatus() == ExpenseReviewStatus.REVIEWED) {
            throw JobFailure.permanent("ALREADY_REVIEWED");
        }
        Map<Long, Clause> byId = new HashMap<>();
        clauses.findAllById(review.getClauseIds()).forEach(c -> byId.put(c.getId(), c));
        Map<Long, RuleDocument> docById = new HashMap<>();
        docs.findAllById(byId.values().stream().map(Clause::getDocId).distinct().toList())
                .forEach(d -> docById.put(d.getId(), d));

        List<ExpenseGuidancePrompt.ClauseInput> inputs = new ArrayList<>();
        for (Long id : review.getClauseIds()) {
            Clause clause = byId.get(id);
            RuleDocument doc = clause == null ? null : docById.get(clause.getDocId());
            if (clause == null || doc == null) {
                continue;
            }
            inputs.add(new ExpenseGuidancePrompt.ClauseInput(clause.getId(), clause.getLabel(), clause.getTitle(),
                    doc.getTitle(), doc.getDocVersion(), doc.getEffectiveDate(), clause.getText()));
        }
        if (inputs.isEmpty()) {
            throw JobFailure.permanent("NO_CANDIDATES");
        }
        ExpenseGuidancePrompt.Subject subject = new ExpenseGuidancePrompt.Subject(review.getItem(), review.getAmount(),
                review.getFundSource(), review.getPlannedDate(), review.getNote(), LocalDate.now(clock.withZone(SEOUL)));
        // 금지·제한이 이 지출과 직접 관련 있는지 가릴 때는 후보를 찾는 데 실제로 쓴 말(항목·설명·찾을 말)을 쓴다.
        String relatedSubject = review.getSearchText() != null ? review.getSearchText() : review.getItem();
        ExpenseGuidancePrompt.Built built = prompt.build(subject, inputs, relatedSubject);
        Map<Long, ExpenseApplicabilityChecker.ClauseText> texts = new HashMap<>();
        Map<Long, String> labelById = new HashMap<>();
        for (ExpenseGuidancePrompt.ClauseInput input : inputs) {
            labelById.put(input.clauseId(), input.docTitle() + " " + input.label());
        }
        for (ExpenseGuidanceValidator.Candidate candidate : built.candidates()) {
            texts.put(candidate.clauseId(), new ExpenseApplicabilityChecker.ClauseText(
                    labelById.get(candidate.clauseId()), candidate.sentText()));
        }
        return new Prepared(built, relatedSubject, subject, texts);
    }

    private static String key(ChecklistKind kind, String text) {
        return kind + "|" + ExpenseGuidanceValidator.squash(text);
    }
}
