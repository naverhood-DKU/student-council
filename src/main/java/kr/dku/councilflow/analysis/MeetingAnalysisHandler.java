package kr.dku.councilflow.analysis;

import java.time.ZoneId;
import java.util.List;
import kr.dku.councilflow.job.JobFailure;
import kr.dku.councilflow.job.JobHandler;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.meeting.Meeting;
import kr.dku.councilflow.meeting.MeetingRepository;
import kr.dku.councilflow.meeting.SegmentRepository;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ANALYZE 작업: 회의록 → AI 분석 → 결정·업무 후보(PROPOSED) 저장. */
@Component
class MeetingAnalysisHandler implements JobHandler<MeetingAnalysisHandler.Result> {

    /** 회의 날짜를 프롬프트에 넣을 때 쓰는 시간대 */
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    record Result(int transcriptVersion, AiAnalysis analysis) {
    }

    private final MeetingRepository meetings;
    private final SegmentRepository segments;
    private final DepartmentRepository departments;
    private final MeetingAnalyzer analyzer;
    private final ProposalWriter writer;
    private final TransactionTemplate readOnly;

    MeetingAnalysisHandler(MeetingRepository meetings, SegmentRepository segments, DepartmentRepository departments,
                           MeetingAnalyzer analyzer, ProposalWriter writer,
                           PlatformTransactionManager transactionManager) {
        this.meetings = meetings;
        this.segments = segments;
        this.departments = departments;
        this.analyzer = analyzer;
        this.writer = writer;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public JobType type() {
        return JobType.ANALYZE;
    }

    @Override
    public Result execute(long meetingId) {
        MeetingSnapshot snapshot = readOnly.execute(status -> snapshot(meetingId));
        if (snapshot.lines().isEmpty()) {
            throw JobFailure.permanent("EMPTY_TRANSCRIPT");
        }
        return new Result(snapshot.transcriptVersion(), analyzer.analyze(snapshot));
    }

    @Override
    public void onSuccess(long meetingId, Result result) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow();
        writer.write(meeting, result.transcriptVersion(), result.analysis());
        meeting.markAnalyzed();
    }

    @Override
    public void onFinalFailure(long meetingId) {
        meetings.findById(meetingId).ifPresent(Meeting::markAnalysisFailed);
    }

    private MeetingSnapshot snapshot(long meetingId) {
        Meeting meeting = meetings.findById(meetingId).orElseThrow(() -> JobFailure.permanent("MEETING_NOT_FOUND"));
        List<MeetingSnapshot.Line> lines = segments
                .findByMeetingIdAndTranscriptVersionOrderBySeq(meetingId, meeting.getTranscriptVersion()).stream()
                .map(s -> new MeetingSnapshot.Line(s.label(), s.getSpeakerLabel(), s.getText()))
                .toList();
        List<String> departmentNames = departments.findByOrganizationIdAndActiveTrueOrderById(meeting.getOrgId()).stream()
                .map(Department::getName)
                .toList();
        return new MeetingSnapshot(meetingId, meeting.getTitle(), meeting.getHeldAt().atZone(KOREA).toLocalDate(),
                meeting.getTranscriptVersion(), departmentNames, lines);
    }
}
