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
import java.time.LocalDate;
import java.util.List;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.JsonColumns;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.org.Department;

/**
 * 결정에 딸린 업무. 결정이 승인되기 전에는 PROPOSED 라서 업무보드에 나오지 않는다.
 * department/dueDate 는 회의에서 명시된 값만, 모르면 null(원칙 2). 추천은 suggestedDepartment 에만 넣는다.
 * 회의 책임자가 직접 추가한 업무는 origin=MANUAL 이고 근거 발언이 없을 수 있다.
 */
@Entity
@Table(name = "task")
public class Task extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false)
    private Decision decision;

    @Column(nullable = false, length = 300)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_department_id")
    private Department suggestedDepartment;

    @Convert(converter = JsonColumns.LongList.class)
    @Column(nullable = false, columnDefinition = "json")
    private List<Long> sourceSegmentIds;

    @Column(length = 100)
    private String ownerDepartmentText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceStatus evidenceStatus;

    @Convert(converter = JsonColumns.StringList.class)
    @Column(columnDefinition = "json")
    private List<String> validationNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskOrigin origin;

    protected Task() {
    }

    public static Task proposal(Decision decision, String title, Department department, LocalDate dueDate,
                                Department suggestedDepartment, String ownerDepartmentText,
                                List<Long> sourceSegmentIds, EvidenceStatus evidenceStatus,
                                List<String> validationNotes) {
        Task task = new Task();
        task.decision = decision;
        task.title = title;
        task.department = department;
        task.dueDate = dueDate;
        task.status = TaskStatus.PROPOSED;
        task.suggestedDepartment = suggestedDepartment;
        task.ownerDepartmentText = ownerDepartmentText;
        task.sourceSegmentIds = List.copyOf(sourceSegmentIds);
        task.evidenceStatus = evidenceStatus;
        task.validationNotes = List.copyOf(validationNotes);
        task.origin = TaskOrigin.AI;
        return task;
    }

    /** 회의 책임자가 직접 추가하는 업무. 결정이 이미 승인됐으면 만든 뒤 activate() 로 바로 업무보드에 올린다. */
    public static Task manual(Decision decision, String title, Department department, LocalDate dueDate) {
        Task task = proposal(decision, title, department, dueDate, null, null, List.of(), EvidenceStatus.OK,
                List.of());
        task.origin = TaskOrigin.MANUAL;
        return task;
    }

    public void rename(String title) {
        ensureEditable();
        this.title = title;
    }

    /** 담당 부서를 바꾼다. 승인된 업무면 상태(UNASSIGNED ↔ ASSIGNED)도 같이 맞춘다. */
    public void assignDepartment(Department department) {
        ensureEditable();
        if (department == null && (status == TaskStatus.IN_PROGRESS || status == TaskStatus.SUBMITTED)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "진행 중인 업무는 담당 부서를 비울 수 없습니다. 다른 부서로 바꾸기만 할 수 있습니다.");
        }
        this.department = department;
        if (status == TaskStatus.UNASSIGNED && department != null) {
            moveTo(TaskStatus.ASSIGNED);
        } else if (status == TaskStatus.ASSIGNED && department == null) {
            moveTo(TaskStatus.UNASSIGNED);
        }
    }

    public void changeDueDate(LocalDate dueDate) {
        ensureEditable();
        this.dueDate = dueDate;
    }

    /** 결정이 승인되면 후보에서 실제 업무가 된다. */
    void activate() {
        moveTo(department == null ? TaskStatus.UNASSIGNED : TaskStatus.ASSIGNED);
    }

    /** 완료·취소된 업무는 내용을 고치지 않는다(끝난 결과를 덮어쓰지 않음). */
    private void ensureEditable() {
        if (status.isFinished()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "완료되었거나 취소된 업무는 고칠 수 없습니다.");
        }
    }

    public void moveTo(TaskStatus target) {
        if (!status.canMoveTo(target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "업무를 " + status + "에서 " + target + "(으)로 바꿀 수 없습니다.");
        }
        if (target == TaskStatus.ASSIGNED && department == null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "담당 부서를 먼저 정해야 합니다.");
        }
        this.status = target;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getTitle() {
        return title;
    }

    public Department getDepartment() {
        return department;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Department getSuggestedDepartment() {
        return suggestedDepartment;
    }

    public List<Long> getSourceSegmentIds() {
        return sourceSegmentIds;
    }

    public String getOwnerDepartmentText() {
        return ownerDepartmentText;
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public List<String> getValidationNotes() {
        return validationNotes == null ? List.of() : validationNotes;
    }

    public TaskOrigin getOrigin() {
        return origin;
    }
}
