package kr.dku.councilflow.expense;

import java.util.Collection;
import java.util.List;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.ExpenseProposal;
import kr.dku.councilflow.decision.Task;
import kr.dku.councilflow.decision.TaskRepository;
import kr.dku.councilflow.decision.TaskStatus;
import kr.dku.councilflow.meeting.Meeting;
import kr.dku.councilflow.org.Department;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회의 분석이 끝난 뒤 돈과 관련된 업무 카드를 규정에서 자동으로 확인한다.
 *
 * 대상 카드: 제목에 돈 관련 낱말·금액이 있거나(MoneySignal), 담당(또는 추천) 부서가 재정·총무·사무 같은 부서인 카드.
 * 대상마다 후보 조항을 찾고(AI 없이), 후보가 있으면 EXPENSE 작업을 만든다. 결과는 업무 카드의 배지가 된다.
 * 표시일 뿐 결정·업무 승인을 막지 않는다. 사람이 승인한다(원칙 1).
 */
@Component
public class RuleCheckPlanner {

    private static final Logger log = LoggerFactory.getLogger(RuleCheckPlanner.class);

    private final DecisionRepository decisions;
    private final TaskRepository tasks;
    private final ExpenseReviewRepository reviews;
    private final ExpenseChecklistItemRepository checklist;
    private final ExpenseReviewService reviewService;
    private final boolean enabled;
    private final int maxPerMeeting;

    RuleCheckPlanner(DecisionRepository decisions, TaskRepository tasks, ExpenseReviewRepository reviews,
                     ExpenseChecklistItemRepository checklist, ExpenseReviewService reviewService,
                     @Value("${app.rules.auto-check.enabled:true}") boolean enabled,
                     @Value("${app.rules.auto-check.max-per-meeting:15}") int maxPerMeeting) {
        this.decisions = decisions;
        this.tasks = tasks;
        this.reviews = reviews;
        this.checklist = checklist;
        this.reviewService = reviewService;
        this.enabled = enabled;
        this.maxPerMeeting = maxPerMeeting;
    }

    /** 회의 분석 결과(결정·업무 후보)를 저장한 같은 트랜잭션 안에서 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void planForMeeting(Meeting meeting) {
        if (!enabled) {
            return;
        }
        List<Decision> meetingDecisions = decisions.findByMeetingIdOrderById(meeting.getId());
        if (meetingDecisions.isEmpty()) {
            return;
        }
        List<Task> candidates = tasks.findByDecisionIdInOrderById(meetingDecisions.stream().map(Decision::getId).toList());
        int made = 0;
        for (Task task : candidates) {
            if (made >= maxPerMeeting) {
                log.info("rule auto-check capped: meeting={} max={}", meeting.getId(), maxPerMeeting);
                break;
            }
            if (check(task, meeting.getOrgId())) {
                made++;
            }
        }
    }

    /** 회장이 직접 추가한 업무 카드도 같은 규칙으로 확인한다. 같은 트랜잭션 안에서 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void planForTask(Task task) {
        if (enabled) {
            check(task, task.getDecision().getMeeting().getOrgId());
        }
    }

    /** 대상 카드이면 후보 조항을 찾고 지출 검토를 만든다. 만들었으면 true. */
    private boolean check(Task task, long orgId) {
        if (!eligible(task)) {
            return false;
        }
        Decision decision = task.getDecision();
        ExpenseProposal proposal = decision.getExpenseProposal();
        String note = decision.getSummary();
        if (proposal != null && proposal.item() != null && !proposal.item().isBlank()) {
            note = proposal.item() + " " + note;
        }
        ExpenseReview created = reviewService.createForTask(orgId, decision.getId(), task.getId(),
                task.getTitle(), proposal == null ? null : proposal.amount(),
                proposal == null ? null : proposal.fundSource(), truncate(note, 600));
        return created != null;
    }

    /** 결정을 지우기 전에, 그 결정에서 자동으로 만든 지출 검토와 체크리스트를 먼저 정리한다(외래키). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearForDecisions(Collection<Long> decisionIds) {
        if (decisionIds.isEmpty()) {
            return;
        }
        List<ExpenseReview> auto = reviews.findByDecisionIdIn(decisionIds).stream()
                .filter(r -> r.getOrigin() == ExpenseReviewOrigin.AUTO)
                .toList();
        for (ExpenseReview review : auto) {
            checklist.deleteByReviewId(review.getId());
        }
        reviews.deleteAll(auto);
        reviews.flush();
    }

    /** 규정 확인 대상 카드인가. 취소된 카드는 제외한다. */
    static boolean eligible(Task task) {
        if (task.getStatus() == TaskStatus.CANCELLED) {
            return false;
        }
        Department department = task.getDepartment() != null ? task.getDepartment() : task.getSuggestedDepartment();
        boolean finance = department != null
                && MoneySignal.isFinanceDepartment(department.getName(), department.getRoleKeywords());
        return finance || MoneySignal.mentionsMoney(task.getTitle(), task.getOwnerDepartmentText());
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
