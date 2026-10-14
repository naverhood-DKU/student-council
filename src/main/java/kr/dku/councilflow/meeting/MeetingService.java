package kr.dku.councilflow.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.audit.AuditService;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.DecisionViews;
import kr.dku.councilflow.job.Job;
import kr.dku.councilflow.job.JobRepository;
import kr.dku.councilflow.job.JobStatus;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.job.JobView;
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.org.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {

    static final int MAX_SEGMENTS = 3000;

    private final OrgAccessGuard accessGuard;
    private final OrganizationRepository organizations;
    private final MeetingRepository meetings;
    private final SegmentRepository segments;
    private final JobRepository jobs;
    private final DecisionRepository decisions;
    private final AuditService audit;

    MeetingService(OrgAccessGuard accessGuard, OrganizationRepository organizations, MeetingRepository meetings,
                   SegmentRepository segments, JobRepository jobs, DecisionRepository decisions, AuditService audit) {
        this.accessGuard = accessGuard;
        this.organizations = organizations;
        this.meetings = meetings;
        this.segments = segments;
        this.jobs = jobs;
        this.decisions = decisions;
        this.audit = audit;
    }

    public record CreateTextMeeting(
            @NotNull Long orgId,
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant heldAt,
            @NotBlank @Size(max = 200_000) String transcript) {
    }

    public record MeetingView(long id, long orgId, String title, Instant heldAt, SourceType sourceType,
                              MeetingStatus status, int transcriptVersion, long version) {

        public static MeetingView of(Meeting meeting) {
            return new MeetingView(meeting.getId(), meeting.getOrgId(), meeting.getTitle(), meeting.getHeldAt(),
                    meeting.getSourceType(), meeting.getStatus(), meeting.getTranscriptVersion(),
                    meeting.getVersion());
        }
    }

    /** segments: 녹음 회의의 전사 대본(화면의 "전사 회의 대본 보기"). 텍스트 회의는 비어 있다. */
    public record MeetingDetail(MeetingView meeting, long segmentCount, JobView latestJob,
                                List<DecisionViews.SegmentView> segments) {
    }

    /** 텍스트 회의록으로 회의를 만든다. 줄 단위로 발언(Segment)을 나눠 저장한다. */
    @Transactional
    public MeetingDetail createFromText(CurrentUser user, CreateTextMeeting request) {
        accessGuard.requireOwner(user, request.orgId());
        List<TranscriptSplitter.Line> lines = TranscriptSplitter.split(request.transcript());
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "회의록에 내용이 없습니다.");
        }
        if (lines.size() > MAX_SEGMENTS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "회의록이 너무 깁니다. 발언 " + MAX_SEGMENTS + "줄 이하로 나눠서 올려 주세요.");
        }
        Organization org = organizations.getReferenceById(request.orgId());
        Meeting meeting = meetings.save(Meeting.fromText(org, request.title().strip(), request.heldAt()));
        int seq = 1;
        for (TranscriptSplitter.Line line : lines) {
            segments.save(new Segment(meeting.getId(), meeting.getTranscriptVersion(), seq++, line.speaker(),
                    line.text()));
        }
        audit.record(user.id(), request.orgId(), "MEETING_CREATED", "MEETING", meeting.getId(), null,
                Map.of("title", meeting.getTitle(), "segmentCount", lines.size()));
        return new MeetingDetail(MeetingView.of(meeting), lines.size(), null, List.of());
    }

    /**
     * PDF 회의록에서 글자만 뽑아 돌려준다. 저장하지 않는다(화면에서 확인·수정한 뒤 createFromText 로 올림).
     * 그룹 owner만 쓸 수 있다.
     */
    public PdfTranscriptExtractor.Extracted extractPdf(CurrentUser user, long orgId, byte[] pdf) {
        accessGuard.requireOwner(user, orgId);
        return PdfTranscriptExtractor.extract(pdf);
    }

    @Transactional(readOnly = true)
    public List<MeetingView> list(CurrentUser user, long orgId) {
        accessGuard.requireMember(user, orgId);
        return meetings.findByOrganizationIdOrderByHeldAtDescIdDesc(orgId).stream().map(MeetingView::of).toList();
    }

    @Transactional(readOnly = true)
    public MeetingDetail get(CurrentUser user, long meetingId) {
        Meeting meeting = loadForMember(user, meetingId);
        long count = segments.countByMeetingIdAndTranscriptVersion(meetingId, meeting.getTranscriptVersion());
        // 녹음 회의는 전사 작업이 먼저 돈다. 분석·전사 중 나중에 만든 작업을 보여 줘야 진행과 실패 이유가 화면에 나온다.
        JobView latest = java.util.stream.Stream.of(JobType.ANALYZE, JobType.TRANSCRIBE)
                .flatMap(type -> jobs.findFirstByTypeAndTargetIdOrderByIdDesc(type, meetingId).stream())
                .max(java.util.Comparator.comparingLong(Job::getId))
                .map(JobView::of).orElse(null);
        List<DecisionViews.SegmentView> transcript = meeting.getSourceType() == SourceType.AUDIO
                ? segments.findByMeetingIdAndTranscriptVersionOrderBySeq(meetingId, meeting.getTranscriptVersion())
                        .stream().map(DecisionViews.SegmentView::of).toList()
                : List.of();
        return new MeetingDetail(MeetingView.of(meeting), count, latest, transcript);
    }

    /**
     * 분석 작업을 만든다. 이미 대기·진행 중인 작업이 있으면 그 작업을 돌려준다.
     * 승인(또는 보류 처리)한 결정이 하나라도 있으면 후보를 갈아엎을 수 없으므로 409.
     */
    @Transactional
    public JobView requestAnalysis(CurrentUser user, long meetingId) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        accessGuard.requireOwner(user, meeting.getOrgId());

        var running = jobs.findFirstByTypeAndTargetIdOrderByIdDesc(JobType.ANALYZE, meetingId)
                .filter(job -> EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING).contains(job.getStatus()));
        if (running.isPresent()) {
            return JobView.of(running.get());
        }
        if (decisions.existsByMeetingIdAndStatusNot(meetingId, DecisionStatus.PROPOSED)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "이미 검토를 마친 결정이 있어 다시 분석할 수 없습니다.");
        }
        Job job = jobs.save(Job.queue(JobType.ANALYZE, meetingId));
        meeting.markAnalyzing();
        audit.record(user.id(), meeting.getOrgId(), "ANALYSIS_REQUESTED", "MEETING", meetingId, null,
                Map.of("jobId", job.getId()));
        return JobView.of(job);
    }

    /** 회의가 속한 그룹의 소속(또는 서비스 관리자 열람)만 통과. 없는 회의도 403(존재 여부를 알려 주지 않음). */
    public Meeting loadForMember(CurrentUser user, long meetingId) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        accessGuard.requireMember(user, meeting.getOrgId());
        return meeting;
    }
}
