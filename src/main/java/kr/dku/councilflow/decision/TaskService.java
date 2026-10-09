package kr.dku.councilflow.decision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.user.MembershipRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 업무 수정과 업무보드.
 *
 * 누가 무엇을 바꿀 수 있나
 * - 승인 전(PROPOSED): 회의 책임자만 제목·담당·기한을 고치거나 후보에서 뺀다(CANCELLED).
 * - 승인 후: 제목·담당·기한·업무 추가·업무 취소는 회의 책임자. 상태는
 *     ASSIGNED → IN_PROGRESS, IN_PROGRESS → SUBMITTED : 담당 부서원 또는 회의 책임자
 *     SUBMITTED → DONE(완료 확정), SUBMITTED → IN_PROGRESS(반려) : 회의 책임자
 *   UNASSIGNED ↔ ASSIGNED 는 담당 부서를 정하거나 비울 때 서버가 맞춘다.
 * - 완료(DONE)·취소(CANCELLED)된 업무는 고칠 수 없다.
 */
@Service
public class TaskService {

    private static final Set<TaskStatus> DEPARTMENT_STEPS = EnumSet.of(TaskStatus.IN_PROGRESS, TaskStatus.SUBMITTED);

    private final TaskRepository tasks;
    private final DecisionRepository decisions;
    private final DepartmentRepository departments;
    private final OrgAccessGuard accessGuard;
    private final DecisionViews views;
    private final AuditService audit;

    TaskService(TaskRepository tasks, DecisionRepository decisions, DepartmentRepository departments,
                OrgAccessGuard accessGuard, DecisionViews views, AuditService audit) {
        this.tasks = tasks;
        this.decisions = decisions;
        this.departments = departments;
        this.accessGuard = accessGuard;
        this.views = views;
        this.audit = audit;
    }

    public record BoardTask(long id, String title, TaskStatus status, TaskOrigin origin, Long departmentId,
                            String departmentName, LocalDate dueDate, long decisionId, String decisionSummary,
                            long meetingId, String meetingTitle, long version) {
    }

    public record AddTask(
            @NotBlank @Size(max = 300) String title,
            Long departmentId,
            LocalDate dueDate) {
    }

    /**
     * 회의 책임자가 업무를 직접 추가한다(AI가 놓친 업무 등).
     * 승인된 결정이면 바로 업무보드에 오르고, 승인 전 결정이면 다른 후보처럼 승인 때 함께 올라간다.
     */
    @Transactional
    public DecisionViews.TaskView add(CurrentUser user, long decisionId, AddTask request) {
        Decision decision = decisions.findById(decisionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        long orgId = decision.getMeeting().getOrgId();
        accessGuard.requireRole(user, orgId, MembershipRole.MEETING_CHAIR);
        DecisionStatus status = decision.getStatus();
        if (status != DecisionStatus.APPROVED && !status.isEditableProposal()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "변경·취소된 결정에는 업무를 추가할 수 없습니다.");
        }
        Department department = request.departmentId() == null ? null : department(request.departmentId(), orgId);
        Task task = Task.manual(decision, request.title().strip(), department, request.dueDate());
        if (status == DecisionStatus.APPROVED) {
            task.activate();
        }
        tasks.saveAndFlush(task);
        audit.record(user.id(), orgId, "TASK_ADDED", "TASK", task.getId(), null, snapshot(task));
        return views.task(task);
    }

    /** 업무보드: 승인된 업무만(후보는 안 보임). departmentId 를 주면 그 부서 것만. */
    @Transactional(readOnly = true)
    public List<BoardTask> board(CurrentUser user, long orgId, Long departmentId) {
        accessGuard.requireMember(user, orgId);
        return tasks.findBoardTasks(orgId).stream()
                .filter(t -> departmentId == null
                        || (t.getDepartment() != null && departmentId.equals(t.getDepartment().getId())))
                .map(t -> new BoardTask(t.getId(), t.getTitle(), t.getStatus(), t.getOrigin(),
                        t.getDepartment() == null ? null : t.getDepartment().getId(),
                        t.getDepartment() == null ? null : t.getDepartment().getName(),
                        t.getDueDate(), t.getDecision().getId(), t.getDecision().getSummary(),
                        t.getDecision().getMeeting().getId(), t.getDecision().getMeeting().getTitle(),
                        t.getVersion()))
                .toList();
    }

    /**
     * 본문에 있는 필드만 바꾼다. 담당·기한을 비우려면 null 을 보낸다.
     * { "title": "...", "departmentId": 3 | null, "dueDate": "2026-10-20" | null, "status": "IN_PROGRESS" }
     */
    @Transactional
    public DecisionViews.TaskView update(CurrentUser user, long taskId, String ifMatch, JsonNode body) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        long orgId = task.getDecision().getMeeting().getOrgId();
        OrgAccess access = accessGuard.requireMember(user, orgId);
        IfMatch.check(ifMatch, task.getVersion());
        if (body == null || !body.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "바꿀 내용을 JSON 객체로 보내 주세요.");
        }

        boolean chair = access.hasAnyRole(MembershipRole.MEETING_CHAIR);
        boolean editsContent = body.has("title") || body.has("departmentId") || body.has("dueDate");
        if (editsContent && !chair) {
            throw new ApiException(ErrorCode.FORBIDDEN, "제목·담당·기한은 회의 책임자만 바꿀 수 있습니다.");
        }
        Map<String, Object> before = snapshot(task);

        if (body.has("title")) {
            String title = body.get("title").isString() ? body.get("title").asString().strip() : "";
            if (title.isEmpty() || title.length() > 300) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "업무 제목은 1~300자로 적어 주세요.");
            }
            task.rename(title);
        }
        if (body.has("departmentId")) {
            task.assignDepartment(departmentFromJson(body.get("departmentId"), orgId));
        }
        if (body.has("dueDate")) {
            task.changeDueDate(date(body.get("dueDate")));
        }
        if (body.has("status")) {
            changeStatus(task, status(body.get("status")), access, chair);
        }

        tasks.flush();
        audit.record(user.id(), orgId, "TASK_UPDATED", "TASK", taskId, before, snapshot(task));
        return views.task(task);
    }

    private static void changeStatus(Task task, TaskStatus target, OrgAccess access, boolean chair) {
        if (target == TaskStatus.CANCELLED) {
            if (!chair) {
                throw new ApiException(ErrorCode.FORBIDDEN, "업무 취소는 회의 책임자만 할 수 있습니다.");
            }
            task.moveTo(TaskStatus.CANCELLED);
            return;
        }
        if (task.getStatus() == TaskStatus.PROPOSED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "결정을 승인한 뒤에 업무 상태를 바꿀 수 있습니다.");
        }
        if (target == TaskStatus.ASSIGNED || target == TaskStatus.UNASSIGNED || target == TaskStatus.PROPOSED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "담당 배정 상태는 departmentId 로 바꿉니다.");
        }
        boolean departmentStep = DEPARTMENT_STEPS.contains(target) && task.getStatus() != TaskStatus.SUBMITTED;
        boolean allowed = chair || (departmentStep && task.getDepartment() != null
                && access.belongsToDepartment(task.getDepartment().getId()));
        if (!allowed) {
            throw new ApiException(ErrorCode.FORBIDDEN, departmentStep
                    ? "담당 부서원만 상태를 바꿀 수 있습니다."
                    : "완료 확정과 반려는 회의 책임자만 할 수 있습니다.");
        }
        task.moveTo(target);
    }

    private Department departmentFromJson(JsonNode node, long orgId) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "departmentId 는 숫자이거나 null 이어야 합니다.");
        }
        return department(node.asLong(), orgId);
    }

    private Department department(long departmentId, long orgId) {
        return departments.findById(departmentId)
                .filter(d -> d.getOrganization().getId() == orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "이 조직의 부서가 아닙니다."));
    }

    private static LocalDate date(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asString());
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "기한은 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private static TaskStatus status(JsonNode node) {
        try {
            return TaskStatus.valueOf(node.asString());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "알 수 없는 업무 상태입니다.");
        }
    }

    private static Map<String, Object> snapshot(Task task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", task.getTitle());
        map.put("departmentId", task.getDepartment() == null ? null : task.getDepartment().getId());
        map.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
        map.put("status", task.getStatus());
        return map;
    }
}
