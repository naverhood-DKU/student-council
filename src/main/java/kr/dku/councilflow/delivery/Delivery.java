package kr.dku.councilflow.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.user.AppUser;

/**
 * 승인된 결정으로 다른 그룹 한 곳에 보낸 업무 요청. (decision, 받는 그룹)마다 하나뿐이다(DB 고유 제약).
 * 받는 조직은 결정 요약과 요청 사항만 본다. 보낸 조직의 회의록·발언은 볼 수 없다(원칙 8).
 */
@Entity
@Table(name = "delivery")
public class Delivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false)
    private Decision decision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_org_id", nullable = false)
    private Organization fromOrganization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_org_id", nullable = false)
    private Organization toOrganization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryStatus status;

    private LocalDate dueDate;

    @Column(columnDefinition = "text")
    private String message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "internal_department_id")
    private Department internalDepartment;

    @Column(columnDefinition = "text")
    private String clarificationReason;

    @Column(columnDefinition = "text")
    private String clarificationReply;

    @Column(columnDefinition = "text")
    private String closingReason;

    private LocalDate previousDueDate;

    private Instant dueChangedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    protected Delivery() {
    }

    public static Delivery send(Decision decision, Organization from, Organization to, RequestType requestType,
                                LocalDate dueDate, String message, AppUser sender) {
        Delivery delivery = new Delivery();
        delivery.decision = decision;
        delivery.fromOrganization = from;
        delivery.toOrganization = to;
        delivery.requestType = requestType;
        delivery.status = DeliveryStatus.PENDING;
        delivery.dueDate = dueDate;
        delivery.message = message;
        delivery.createdBy = sender;
        return delivery;
    }

    // ── 받는 조직이 하는 일 ──

    /** 수락. 알림(INFORM)은 수락하면 바로 완료다. */
    void accept() {
        moveTo(DeliveryStatus.ACCEPTED);
        if (requestType == RequestType.INFORM) {
            moveTo(DeliveryStatus.DONE);
        }
    }

    void requestClarification(String reason) {
        moveTo(DeliveryStatus.CLARIFICATION_REQUESTED);
        this.clarificationReason = reason;
        this.clarificationReply = null;
    }

    void markNotApplicable(String reason) {
        moveTo(DeliveryStatus.NOT_APPLICABLE);
        this.closingReason = reason;
    }

    void start() {
        moveTo(DeliveryStatus.IN_PROGRESS);
    }

    void assignInternalDepartment(Department department) {
        ensureOpen();
        this.internalDepartment = department;
    }

    /** 결과 제출. 수락만 하고 시작을 안 눌렀어도 제출하면 진행을 거쳐 제출 상태가 된다. */
    void submitted() {
        if (status == DeliveryStatus.ACCEPTED) {
            moveTo(DeliveryStatus.IN_PROGRESS);
        }
        moveTo(DeliveryStatus.SUBMITTED);
    }

    // ── 보낸 조직이 하는 일 ──

    void answerClarification(String reply) {
        moveTo(DeliveryStatus.PENDING);
        this.clarificationReply = reply;
    }

    void cancel(String reason) {
        moveTo(DeliveryStatus.CANCELLED);
        this.closingReason = reason;
    }

    /** 기한 변경. 이전 값을 남겨 수신함에 "기한 변경됨: 이전 → 이후"로 보여 준다. 끝난 전달은 바꾸지 않는다. */
    void changeDueDate(LocalDate newDueDate, Instant at) {
        ensureOpen();
        if (Objects.equals(dueDate, newDueDate)) {
            return;
        }
        this.previousDueDate = dueDate;
        this.dueDate = newDueDate;
        this.dueChangedAt = at;
    }

    void approveResult() {
        moveTo(DeliveryStatus.DONE);
    }

    void returnResult() {
        moveTo(DeliveryStatus.IN_PROGRESS);
    }

    private void ensureOpen() {
        if (status.isFinished()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "끝난 전달은 바꿀 수 없습니다.");
        }
    }

    private void moveTo(DeliveryStatus target) {
        if (!status.canMoveTo(target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "전달 상태를 " + status + "에서 " + target + "(으)로 바꿀 수 없습니다.");
        }
        this.status = target;
    }

    public Decision getDecision() {
        return decision;
    }

    public Organization getFromOrganization() {
        return fromOrganization;
    }

    public Organization getToOrganization() {
        return toOrganization;
    }

    public RequestType getRequestType() {
        return requestType;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getMessage() {
        return message;
    }

    public Department getInternalDepartment() {
        return internalDepartment;
    }

    public String getClarificationReason() {
        return clarificationReason;
    }

    public String getClarificationReply() {
        return clarificationReply;
    }

    public String getClosingReason() {
        return closingReason;
    }

    public LocalDate getPreviousDueDate() {
        return previousDueDate;
    }

    public Instant getDueChangedAt() {
        return dueChangedAt;
    }
}
