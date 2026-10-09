package kr.dku.councilflow.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.JsonColumns;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.meeting.Meeting;
import kr.dku.councilflow.user.AppUser;

/** 회의 결정. AI가 만든 후보는 PROPOSED 로 시작하고, 그룹 회장(owner)이 승인해야 확정된다(원칙 1). */
@Entity
@Table(name = "decision")
public class Decision extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(nullable = false, length = 300)
    private String agenda;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DecisionStatus status;

    /** 근거 발언(Segment) id 목록 */
    @Convert(converter = JsonColumns.LongList.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<Long> sourceSegmentIds;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AiDecisionStatus aiStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceStatus evidenceStatus;

    @Convert(converter = JsonColumns.StringList.class)
    @Column(columnDefinition = "json")
    private List<String> validationNotes;

    @Convert(converter = ExpenseProposal.Converter.class)
    @Column(columnDefinition = "json")
    private ExpenseProposal expenseProposal;

    protected Decision() {
    }

    public static Decision proposal(Meeting meeting, String agenda, String summary, AiDecisionStatus aiStatus,
                                    List<Long> sourceSegmentIds, EvidenceStatus evidenceStatus,
                                    List<String> validationNotes, ExpenseProposal expenseProposal) {
        Decision decision = new Decision();
        decision.meeting = meeting;
        decision.agenda = agenda;
        decision.summary = summary;
        decision.status = DecisionStatus.PROPOSED;
        decision.aiStatus = aiStatus;
        decision.sourceSegmentIds = List.copyOf(sourceSegmentIds);
        decision.evidenceStatus = evidenceStatus;
        decision.validationNotes = List.copyOf(validationNotes);
        decision.expenseProposal = expenseProposal;
        return decision;
    }

    /** 승인 전 사람이 요약·안건 문구를 고친다. */
    public void edit(String agenda, String summary) {
        if (!status.isEditableProposal()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "승인된 결정은 이 화면에서 고칠 수 없습니다.");
        }
        if (agenda != null) {
            this.agenda = agenda;
        }
        if (summary != null) {
            this.summary = summary;
        }
    }

    public void approve(AppUser approver) {
        moveTo(DecisionStatus.APPROVED);
        this.approvedBy = approver;
    }

    private void moveTo(DecisionStatus target) {
        if (!status.canMoveTo(target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "결정을 " + status + "에서 " + target + "(으)로 바꿀 수 없습니다.");
        }
        this.status = target;
    }

    public Meeting getMeeting() {
        return meeting;
    }

    public String getAgenda() {
        return agenda;
    }

    public String getSummary() {
        return summary;
    }

    public DecisionStatus getStatus() {
        return status;
    }

    public List<Long> getSourceSegmentIds() {
        return sourceSegmentIds;
    }

    public AppUser getApprovedBy() {
        return approvedBy;
    }

    public AiDecisionStatus getAiStatus() {
        return aiStatus;
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public List<String> getValidationNotes() {
        return validationNotes == null ? List.of() : validationNotes;
    }

    public ExpenseProposal getExpenseProposal() {
        return expenseProposal;
    }
}
