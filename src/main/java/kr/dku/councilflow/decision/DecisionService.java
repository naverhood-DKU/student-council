package kr.dku.councilflow.decision;

import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.common.web.IfMatch;
import kr.dku.councilflow.meeting.Meeting;
import kr.dku.councilflow.meeting.MeetingService;
import kr.dku.councilflow.meeting.Segment;
import kr.dku.councilflow.meeting.SegmentRepository;
import kr.dku.councilflow.org.DepartmentRepository;
import kr.dku.councilflow.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 후보 조회, 승인 전 수정, 승인. 승인과 수정은 회의를 연 그룹의 owner(회장)만 할 수 있다. */
@Service
public class DecisionService {

    private final DecisionRepository decisions;
    private final TaskRepository tasks;
    private final SegmentRepository segments;
    private final DepartmentRepository departments;
    private final AppUserRepository users;
    private final MeetingService meetingService;
    private final OrgAccessGuard accessGuard;
    private final DecisionViews views;
    private final AuditService audit;

    DecisionService(DecisionRepository decisions, TaskRepository tasks, SegmentRepository segments,
                    DepartmentRepository departments, AppUserRepository users, MeetingService meetingService,
                    OrgAccessGuard accessGuard, DecisionViews views, AuditService audit) {
        this.decisions = decisions;
        this.tasks = tasks;
        this.segments = segments;
        this.departments = departments;
        this.users = users;
        this.meetingService = meetingService;
        this.accessGuard = accessGuard;
        this.views = views;
        this.audit = audit;
    }

    public record Proposals(
            MeetingService.MeetingView meeting,
            List<DepartmentOption> departments,
            List<DecisionViews.SegmentView> segments,
            List<DecisionViews.DecisionView> decisions) {
    }

    public record DepartmentOption(long id, String name) {
    }

    public record EditDecision(@Size(max = 300) String agenda, @Size(max = 2000) String summary) {
    }

    /**
     * 업무 추가·지출 절차 확인 화면에서 고를 결정(승인된 것만).
     * expenseProposal 은 회의에서 돈을 쓰기로 한 언급이 있을 때만 있다(지출 절차 확인 값을 미리 채우는 데 쓴다).
     */
    public record DecisionOption(long id, String agenda, String summary, long meetingId, String meetingTitle,
                                 ExpenseProposal expenseProposal) {
    }

    @Transactional(readOnly = true)
    public List<DecisionOption> approvedDecisions(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return decisions.findByOrgAndStatus(orgId, DecisionStatus.APPROVED).stream()
                .map(d -> new DecisionOption(d.getId(), d.getAgenda(), d.getSummary(), d.getMeeting().getId(),
                        d.getMeeting().getTitle(), d.getExpenseProposal()))
                .toList();
    }

    /** 회의의 결정·업무 후보 전체와 회의록(현재 전사 버전). 같은 조직 소속만 볼 수 있다. */
    @Transactional(readOnly = true)
    public Proposals proposals(CurrentUser user, long meetingId) {
        Meeting meeting = meetingService.loadForMember(user, meetingId);
        List<Segment> transcript = segments.findByMeetingIdAndTranscriptVersionOrderBySeq(meetingId,
                meeting.getTranscriptVersion());
        return new Proposals(
                MeetingService.MeetingView.of(meeting),
                departments.findByOrganizationIdAndActiveTrueOrderById(meeting.getOrgId()).stream()
                        .map(d -> new DepartmentOption(d.getId(), d.getName())).toList(),
                transcript.stream().map(DecisionViews.SegmentView::of).toList(),
                views.decisions(decisions.findByMeetingIdOrderById(meetingId), transcript));
    }

    @Transactional
    public DecisionViews.DecisionView edit(CurrentUser user, long decisionId, String ifMatch, EditDecision request) {
        Decision decision = loadForChair(user, decisionId);
        IfMatch.check(ifMatch, decision.getVersion());
        Map<String, Object> before = snapshot(decision);
        decision.edit(blankToNull(request.agenda()), blankToNull(request.summary()));
        decisions.flush();
        audit.record(user.id(), decision.getMeeting().getOrgId(), "DECISION_EDITED", "DECISION", decisionId,
                before, snapshot(decision));
        return views.decision(decision);
    }

    /** 승인: 결정은 APPROVED, 딸린 업무 후보는 담당 부서가 있으면 ASSIGNED, 없으면 UNASSIGNED 로 업무보드에 올라간다. */
    @Transactional
    public DecisionViews.DecisionView approve(CurrentUser user, long decisionId, String ifMatch) {
        Decision decision = loadForChair(user, decisionId);
        IfMatch.check(ifMatch, decision.getVersion());
        Map<String, Object> before = snapshot(decision);
        decision.approve(users.getReferenceById(user.id()));
        // 승인 전에 후보에서 뺀(CANCELLED) 업무는 그대로 둔다.
        List<Task> decisionTasks = tasks.findByDecisionIdOrderById(decisionId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PROPOSED)
                .toList();
        decisionTasks.forEach(Task::activate);
        decisions.flush();
        Map<String, Object> after = snapshot(decision);
        after.put("taskIds", decisionTasks.stream().map(Task::getId).toList());
        audit.record(user.id(), decision.getMeeting().getOrgId(), "DECISION_APPROVED", "DECISION", decisionId,
                before, after);
        return views.decision(decision);
    }

    private Decision loadForChair(CurrentUser user, long decisionId) {
        Decision decision = decisions.findById(decisionId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        accessGuard.requireOwner(user, decision.getMeeting().getOrgId());
        return decision;
    }

    private static Map<String, Object> snapshot(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", decision.getStatus());
        map.put("agenda", decision.getAgenda());
        map.put("summary", decision.getSummary());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
