package kr.dku.councilflow.meeting;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.Task;
import kr.dku.councilflow.decision.TaskItemRepository;
import kr.dku.councilflow.decision.TaskRepository;
import kr.dku.councilflow.delivery.Delivery;
import kr.dku.councilflow.delivery.DeliveryRepository;
import kr.dku.councilflow.delivery.SubmissionRepository;
import kr.dku.councilflow.expense.ExpenseReviewOrigin;
import kr.dku.councilflow.expense.ExpenseReviewRepository;
import kr.dku.councilflow.expense.RuleCheckPlanner;
import kr.dku.councilflow.job.JobRepository;
import kr.dku.councilflow.job.JobStatus;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 회의 완전 삭제(회장만). 회의록·발언·AI 후보·승인된 결정, 그 결정의 업무·체크리스트,
 * 그 결정으로 다른 그룹에 보낸 요청과 제출 결과, 분석·전사 작업 기록까지 지운다. 되돌릴 수 없다.
 * - 사람이 요청한 지출 검토는 그룹의 별도 기록이라 지우지 않고 결정과의 연결만 끊는다.
 *   업무 카드마다 자동으로 만든 규정 확인(AUTO 지출 검토)은 업무와 함께 지운다(RuleCheckPlanner).
 * - 분석·전사 작업이 대기·진행 중이면 409(작업이 끝난 뒤 지운다).
 * - 녹음 회의는 저장소(로컬 폴더·NCP Object Storage)의 원본 파일도 지운다. DB 삭제가 확정된 뒤에 지우고,
 *   저장소 오류로 못 지우면 회의 삭제는 그대로 두고 로그(회의 id·오류 종류)만 남긴다.
 * - 무엇을 지웠는지는 감사 기록(MEETING_DELETED)에 남긴다.
 */
@Service
public class MeetingDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MeetingDeletionService.class);

    private static final EnumSet<JobType> MEETING_JOBS = EnumSet.of(JobType.ANALYZE, JobType.TRANSCRIBE);
    private static final EnumSet<JobStatus> UNFINISHED = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);

    private final MeetingRepository meetings;
    private final SegmentRepository segments;
    private final DecisionRepository decisions;
    private final TaskRepository tasks;
    private final TaskItemRepository taskItems;
    private final DeliveryRepository deliveries;
    private final SubmissionRepository submissions;
    private final ExpenseReviewRepository expenseReviews;
    private final RuleCheckPlanner ruleChecks;
    private final JobRepository jobs;
    private final OrgAccessGuard accessGuard;
    private final AuditService audit;
    private final ObjectStorage storage;

    MeetingDeletionService(MeetingRepository meetings, SegmentRepository segments, DecisionRepository decisions,
                           TaskRepository tasks, TaskItemRepository taskItems, DeliveryRepository deliveries,
                           SubmissionRepository submissions, ExpenseReviewRepository expenseReviews,
                           RuleCheckPlanner ruleChecks, JobRepository jobs, OrgAccessGuard accessGuard,
                           AuditService audit, ObjectStorage storage) {
        this.meetings = meetings;
        this.segments = segments;
        this.decisions = decisions;
        this.tasks = tasks;
        this.taskItems = taskItems;
        this.deliveries = deliveries;
        this.submissions = submissions;
        this.expenseReviews = expenseReviews;
        this.ruleChecks = ruleChecks;
        this.jobs = jobs;
        this.accessGuard = accessGuard;
        this.audit = audit;
        this.storage = storage;
    }

    /** 지우기 전에 화면에서 보여 줄 영향 범위 */
    public record DeletionImpact(String title, int decisionCount, long approvedDecisionCount, int taskCount,
                                 long checklistItemCount, int deliveryCount, List<String> receiverGroups,
                                 long expenseReviewCount, boolean jobRunning, boolean hasRecording) {
    }

    @Transactional(readOnly = true)
    public DeletionImpact impact(CurrentUser user, long meetingId) {
        Meeting meeting = ownedMeeting(user, meetingId);
        return collect(meeting).impact();
    }

    @Transactional
    public void delete(CurrentUser user, long meetingId) {
        Meeting meeting = ownedMeeting(user, meetingId);
        Targets targets = collect(meeting);
        if (targets.impact().jobRunning()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "분석이나 전사가 진행 중입니다. 끝난 뒤에 지워 주세요.");
        }
        Map<String, Object> before = snapshot(meeting, targets);

        if (!targets.taskIds().isEmpty()) {
            taskItems.deleteByTaskIds(targets.taskIds());
            tasks.deleteAllByIdInBatch(targets.taskIds());
        }
        if (!targets.deliveryIds().isEmpty()) {
            submissions.deleteByDeliveryIds(targets.deliveryIds());
            deliveries.deleteAllByIdInBatch(targets.deliveryIds());
        }
        if (!targets.decisionIds().isEmpty()) {
            ruleChecks.clearForDecisions(targets.decisionIds());
            expenseReviews.detachDecisions(targets.decisionIds());
            decisions.deleteAllByIdInBatch(targets.decisionIds());
        }
        segments.deleteByMeetingId(meetingId);
        jobs.deleteByTypesAndTargetId(MEETING_JOBS, meetingId);
        // 위에서 읽어 둔 결정 엔티티가 아직 이 회의를 가리키므로, 회의도 같은 방식(일괄 삭제 쿼리)으로 지운다.
        meetings.deleteAllByIdInBatch(List.of(meetingId));
        audit.record(user.id(), meeting.getOrgId(), "MEETING_DELETED", "MEETING", meetingId, before, null);
        if (hasRecording(meeting)) {
            deleteRecordingAfterCommit(meetingId, meeting.getFileKey());
        }
    }

    private static boolean hasRecording(Meeting meeting) {
        return meeting.getFileKey() != null && !meeting.getFileKey().isBlank();
    }

    /** DB 삭제가 되돌려지면 파일은 남겨야 하므로 커밋 뒤에 지운다. */
    private void deleteRecordingAfterCommit(long meetingId, String fileKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(fileKey);
                } catch (RuntimeException e) {
                    log.warn("meeting={} recording delete failed: {}", meetingId, e.getClass().getSimpleName());
                }
            }
        });
    }

    private Meeting ownedMeeting(CurrentUser user, long meetingId) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        accessGuard.requireOwner(user, meeting.getOrgId());
        return meeting;
    }

    private record Targets(List<Long> decisionIds, List<Long> taskIds, List<Long> deliveryIds,
                           List<Long> receiverOrgIds, DeletionImpact impact) {
    }

    private Targets collect(Meeting meeting) {
        long meetingId = meeting.getId();
        List<Decision> meetingDecisions = decisions.findByMeetingIdOrderById(meetingId);
        List<Long> decisionIds = meetingDecisions.stream().map(Decision::getId).toList();
        List<Long> taskIds = decisionIds.isEmpty() ? List.of()
                : tasks.findByDecisionIdInOrderById(decisionIds).stream().map(Task::getId).toList();
        List<Delivery> sent = decisionIds.isEmpty() ? List.of() : deliveries.findByDecisionIds(decisionIds);
        boolean jobRunning = MEETING_JOBS.stream()
                .map(type -> jobs.findFirstByTypeAndTargetIdOrderByIdDesc(type, meetingId))
                .anyMatch(job -> job.filter(j -> UNFINISHED.contains(j.getStatus())).isPresent());
        DeletionImpact impact = new DeletionImpact(
                meeting.getTitle(),
                meetingDecisions.size(),
                meetingDecisions.stream().filter(d -> d.getStatus() != DecisionStatus.PROPOSED).count(),
                taskIds.size(),
                taskIds.isEmpty() ? 0 : taskItems.countByTaskIdIn(taskIds),
                sent.size(),
                sent.stream().map(d -> d.getToOrganization().getName()).distinct().toList(),
                decisionIds.isEmpty() ? 0 : expenseReviews.findByDecisionIdIn(decisionIds).stream()
                        .filter(r -> r.getOrigin() == ExpenseReviewOrigin.MANUAL).count(),
                jobRunning,
                hasRecording(meeting));
        return new Targets(decisionIds, taskIds, sent.stream().map(Delivery::getId).toList(),
                sent.stream().map(d -> d.getToOrganization().getId()).distinct().toList(), impact);
    }

    private static Map<String, Object> snapshot(Meeting meeting, Targets targets) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", meeting.getTitle());
        map.put("heldAt", meeting.getHeldAt().toString());
        map.put("decisionIds", targets.decisionIds());
        map.put("taskIds", targets.taskIds());
        map.put("deliveryIds", targets.deliveryIds());
        map.put("receiverOrgIds", targets.receiverOrgIds());
        map.put("recording", hasRecording(meeting));
        return map;
    }
}
