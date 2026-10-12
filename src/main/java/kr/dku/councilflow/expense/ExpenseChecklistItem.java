package kr.dku.councilflow.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.JsonColumns;

/** 지출 검토의 체크리스트 한 줄(필요한 승인 또는 필요한 증빙). 체크 상태만 사람이 바꾼다. */
@Entity
@Table(name = "expense_checklist_item")
public class ExpenseChecklistItem extends BaseEntity {

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistKind kind;

    @Column(nullable = false, length = 500)
    private String text;

    @Convert(converter = JsonColumns.LongList.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<Long> clauseIds;

    @Column(nullable = false, length = 1000)
    private String quote;

    @Column(nullable = false)
    private boolean checked;

    private Long checkedBy;

    private Instant checkedAt;

    protected ExpenseChecklistItem() {
    }

    public ExpenseChecklistItem(Long reviewId, int seq, ChecklistKind kind, String text, List<Long> clauseIds,
                                String quote) {
        this.reviewId = reviewId;
        this.seq = seq;
        this.kind = kind;
        this.text = text;
        this.clauseIds = List.copyOf(clauseIds);
        this.quote = quote;
    }

    /** 다시 받은 AI 안내에 같은 항목이 있으면 사람이 해 둔 체크를 그대로 이어 준다. */
    void carryOver(ExpenseChecklistItem previous) {
        this.checked = previous.checked;
        this.checkedBy = previous.checkedBy;
        this.checkedAt = previous.checkedAt;
    }

    void setChecked(boolean checked, Long userId, Instant now) {
        this.checked = checked;
        this.checkedBy = checked ? userId : null;
        this.checkedAt = checked ? now : null;
    }

    public Long getReviewId() { return reviewId; }
    public int getSeq() { return seq; }
    public ChecklistKind getKind() { return kind; }
    public String getText() { return text; }
    public List<Long> getClauseIds() { return clauseIds == null ? List.of() : clauseIds; }
    public String getQuote() { return quote; }
    public boolean isChecked() { return checked; }
    public Long getCheckedBy() { return checkedBy; }
    public Instant getCheckedAt() { return checkedAt; }
}
