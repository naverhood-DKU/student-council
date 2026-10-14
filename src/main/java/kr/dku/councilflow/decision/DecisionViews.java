package kr.dku.councilflow.decision;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kr.dku.councilflow.expense.TaskRuleChecks;
import kr.dku.councilflow.meeting.Segment;
import kr.dku.councilflow.meeting.SegmentRepository;
import kr.dku.councilflow.org.Department;
import org.springframework.stereotype.Component;

/** 결정·업무를 화면용 모양으로 바꾼다. 후보마다 근거 발언 원문(evidence)을 같이 붙인다. */
@Component
public class DecisionViews {

    private final SegmentRepository segments;
    private final TaskRepository tasks;
    private final TaskRuleChecks ruleChecks;

    DecisionViews(SegmentRepository segments, TaskRepository tasks, TaskRuleChecks ruleChecks) {
        this.segments = segments;
        this.tasks = tasks;
        this.ruleChecks = ruleChecks;
    }

    public record SegmentView(long id, String label, String speaker, String text) {
        public static SegmentView of(Segment segment) {
            return new SegmentView(segment.getId(), segment.label(), segment.getSpeakerLabel(), segment.getText());
        }
    }

    public record TaskView(
            long id,
            long decisionId,
            String title,
            TaskStatus status,
            Long departmentId,
            String departmentName,
            /** AI가 업무 내용으로 고른 담당인지. 회장이 다른 부서로 바꾸면 false */
            boolean departmentByAi,
            /** AI가 그 부서를 고른 이유 */
            String departmentReason,
            String ownerDepartmentText,
            Long suggestedDepartmentId,
            String suggestedDepartmentName,
            LocalDate dueDate,
            EvidenceStatus evidenceStatus,
            List<String> validationNotes,
            List<Long> sourceSegmentIds,
            List<SegmentView> evidence,
            TaskOrigin origin,
            long version,
            /** 돈과 관련된 카드의 규정 확인 표시. 확인 대상이 아니면 null */
            TaskRuleChecks.RuleCheckView ruleCheck) {
    }

    public record DecisionView(
            long id,
            long meetingId,
            String agenda,
            String summary,
            DecisionStatus status,
            AiDecisionStatus aiStatus,
            EvidenceStatus evidenceStatus,
            List<String> validationNotes,
            List<Long> sourceSegmentIds,
            List<SegmentView> evidence,
            ExpenseProposal expenseProposal,
            String approvedByName,
            long version,
            List<TaskView> tasks) {
    }

    /** 여러 결정을 한 번에 바꾼다. knownSegments 에 없는 근거 발언은 DB에서 더 찾아온다. */
    public List<DecisionView> decisions(List<Decision> decisions, Collection<Segment> knownSegments) {
        List<Task> allTasks = decisions.isEmpty()
                ? List.of()
                : tasks.findByDecisionIdInOrderById(decisions.stream().map(Decision::getId).toList());
        Map<Long, SegmentView> segmentById = segmentMap(decisions, allTasks, knownSegments);
        Map<Long, TaskRuleChecks.RuleCheckView> checks =
                ruleChecks.byTask(allTasks.stream().map(Task::getId).toList());
        Map<Long, List<Task>> tasksByDecision = allTasks.stream()
                .collect(Collectors.groupingBy(t -> t.getDecision().getId()));
        return decisions.stream()
                .map(d -> decision(d, tasksByDecision.getOrDefault(d.getId(), List.of()), segmentById, checks))
                .toList();
    }

    public DecisionView decision(Decision decision) {
        return decisions(List.of(decision), List.of()).get(0);
    }

    public TaskView task(Task task) {
        Map<Long, SegmentView> segmentById = segmentMap(List.of(), List.of(task), List.of());
        return task(task, segmentById, ruleChecks.byTask(List.of(task.getId())));
    }

    private DecisionView decision(Decision d, List<Task> decisionTasks, Map<Long, SegmentView> segmentById,
                                  Map<Long, TaskRuleChecks.RuleCheckView> checks) {
        return new DecisionView(d.getId(), d.getMeeting().getId(), d.getAgenda(), d.getSummary(), d.getStatus(),
                d.getAiStatus(), d.getEvidenceStatus(), d.getValidationNotes(), d.getSourceSegmentIds(),
                evidence(d.getSourceSegmentIds(), segmentById), d.getExpenseProposal(),
                d.getApprovedBy() == null ? null : d.getApprovedBy().getName(), d.getVersion(),
                decisionTasks.stream().map(t -> task(t, segmentById, checks)).toList());
    }

    private TaskView task(Task t, Map<Long, SegmentView> segmentById,
                          Map<Long, TaskRuleChecks.RuleCheckView> checks) {
        Department department = t.getDepartment();
        Department suggested = t.getSuggestedDepartment();
        return new TaskView(t.getId(), t.getDecision().getId(), t.getTitle(), t.getStatus(),
                department == null ? null : department.getId(),
                department == null ? null : department.getName(),
                t.isDepartmentByAi(), t.getDepartmentReason(),
                t.getOwnerDepartmentText(),
                suggested == null ? null : suggested.getId(),
                suggested == null ? null : suggested.getName(),
                t.getDueDate(), t.getEvidenceStatus(), t.getValidationNotes(), t.getSourceSegmentIds(),
                evidence(t.getSourceSegmentIds(), segmentById), t.getOrigin(), t.getVersion(),
                checks.get(t.getId()));
    }

    private static List<SegmentView> evidence(List<Long> ids, Map<Long, SegmentView> segmentById) {
        return ids.stream().map(segmentById::get).filter(Objects::nonNull).toList();
    }

    private Map<Long, SegmentView> segmentMap(List<Decision> decisions, List<Task> taskList,
                                              Collection<Segment> knownSegments) {
        Map<Long, SegmentView> byId = knownSegments.stream()
                .collect(Collectors.toMap(Segment::getId, SegmentView::of, (a, b) -> a, HashMap::new));
        Set<Long> missing = new HashSet<>();
        decisions.forEach(d -> missing.addAll(d.getSourceSegmentIds()));
        taskList.forEach(t -> missing.addAll(t.getSourceSegmentIds()));
        missing.removeAll(byId.keySet());
        if (!missing.isEmpty()) {
            segments.findByIdIn(missing).forEach(s -> byId.put(s.getId(), SegmentView.of(s)));
        }
        return byId;
    }
}
