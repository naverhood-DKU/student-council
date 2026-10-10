package kr.dku.councilflow.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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

    @Convert(converter = JsonColumns.LongList.class)
    @Column(columnDefinition = "json")
    private List<Long> clauseIds;

    private Long requestedBy;

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

    public Long getOrgId() { return orgId; }
    public Long getDecisionId() { return decisionId; }
    public String getItem() { return item; }
    public Long getAmount() { return amount; }
    public String getFundSource() { return fundSource; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public String getNote() { return note; }
    public String getSearchText() { return searchText; }
    public ExpenseReviewStatus getStatus() { return status; }
    public List<Long> getClauseIds() { return clauseIds == null ? List.of() : clauseIds; }
    public Long getRequestedBy() { return requestedBy; }
}
