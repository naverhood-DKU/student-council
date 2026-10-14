package kr.dku.councilflow.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.JsonColumns;

/** 지출 한 건에 대한 절차 검토 요청. 후보 조항 id(clauseIds)는 검색 결과이며, 조항 원문은 Clause 에서 읽는다. */
@Entity
@Table(name = "expense_review")
public class ExpenseReview extends BaseEntity {

    @Column(nullable = false)
    private Long orgId;

    private Long decisionId;

    /** 회의 분석 뒤 자동으로 확인한 업무 카드. 사람이 요청한 건은 null */
    private Long taskId;

    @Column(nullable = false, length = 200)
    private String item;

    private Long amount;

    @Column(length = 100)
    private String fundSource;

    private LocalDate plannedDate;

    @Column(length = 1000)
    private String note;

    @Column(length = 600)
    private String searchText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseReviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpenseReviewOrigin origin = ExpenseReviewOrigin.MANUAL;

    @Convert(converter = JsonColumns.LongList.class)
    @Column(columnDefinition = "json")
    private List<Long> clauseIds;

    private Long requestedBy;

    /** AI 가 더 있어야 절차를 가릴 수 있다고 한 사실. 서버 검증을 통과한 AI 안내와 함께 저장한다. */
    @Convert(converter = JsonColumns.StringList.class)
    @Column(name = "missing_facts", columnDefinition = "json")
    private List<String> missingFacts;

    /** 서버 검증을 통과한 AI 참고 안내문. 조항으로 확인된 내용이 아니다. */
    @Column(columnDefinition = "text")
    private String guidance;

    @Convert(converter = GuidanceResult.Converter.class)
    @Column(name = "ai_result", columnDefinition = "json")
    private GuidanceResult aiResult;

    private Long reviewedBy;

    private Instant reviewedAt;

    protected ExpenseReview() {
    }

    public ExpenseReview(Long orgId, Long decisionId, String item, Long amount, String fundSource,
                         LocalDate plannedDate, String note, String searchText, ExpenseReviewStatus status,
                         List<Long> clauseIds, Long requestedBy) {
        this.orgId = orgId;
        this.decisionId = decisionId;
        this.item = item;
        this.amount = amount;
        this.fundSource = fundSource;
        this.plannedDate = plannedDate;
        this.note = note;
        this.searchText = searchText;
        this.status = status;
        this.clauseIds = List.copyOf(clauseIds);
        this.requestedBy = requestedBy;
    }

    /**
     * 회의 분석이 끝난 뒤 업무 카드 하나에 대해 서버가 자동으로 만든 검토. 요청한 사람이 없다(requestedBy = null).
     */
    public static ExpenseReview auto(Long orgId, Long decisionId, Long taskId, String item, Long amount,
                                     String fundSource, String note, String searchText,
                                     ExpenseReviewStatus status, List<Long> clauseIds) {
        ExpenseReview review = new ExpenseReview(orgId, decisionId, item, amount, fundSource, null, note, searchText,
                status, clauseIds, null);
        review.taskId = taskId;
        review.origin = ExpenseReviewOrigin.AUTO;
        return review;
    }

    public Long getOrgId() { return orgId; }
    public Long getDecisionId() { return decisionId; }
    public Long getTaskId() { return taskId; }
    public ExpenseReviewOrigin getOrigin() { return origin; }
    public String getItem() { return item; }
    public Long getAmount() { return amount; }
    public String getFundSource() { return fundSource; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public String getNote() { return note; }
    public String getSearchText() { return searchText; }
    public ExpenseReviewStatus getStatus() { return status; }
    public List<Long> getClauseIds() { return clauseIds == null ? List.of() : clauseIds; }
    public Long getRequestedBy() { return requestedBy; }
    public List<String> getMissingFacts() { return missingFacts == null ? List.of() : missingFacts; }
    public String getGuidance() { return guidance; }
    public GuidanceResult getAiResult() { return aiResult; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }

    /**
     * 서버 검증을 통과한 AI 안내를 붙이고 상태를 정한다. 사람이 이미 검토 완료한 건은 호출하는 쪽에서 막는다.
     * status 는 PROCEDURE_FOUND / POSSIBLE_CONFLICT / NEEDS_CHECK 중 하나다("문제없음" 상태는 없다).
     */
    void applyGuidance(ExpenseReviewStatus status, GuidanceResult result, List<String> missingFacts, String guidance) {
        this.status = status;
        this.aiResult = result;
        this.missingFacts = List.copyOf(missingFacts);
        this.guidance = guidance;
    }

    /** 사람이 AI 안내와 조항 원문을 확인했다는 표시. "규정에 맞다"는 뜻이 아니다. */
    void markReviewed(Long userId, Instant now) {
        this.status = ExpenseReviewStatus.REVIEWED;
        this.reviewedBy = userId;
        this.reviewedAt = now;
    }
}
