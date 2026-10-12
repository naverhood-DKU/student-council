package kr.dku.councilflow.meeting;

import java.util.ArrayList;
import java.util.List;
import kr.dku.councilflow.ai.SpeechClient;
import kr.dku.councilflow.job.JobFailure;
import kr.dku.councilflow.job.JobHandler;
import kr.dku.councilflow.job.JobType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TranscribeJobHandler implements JobHandler<SpeechClient.SpeechTranscriptionResult> {

    private final MeetingRepository meetings;
    private final SegmentRepository segments;
    private final SpeechClient speechClient;

    public TranscribeJobHandler(
            MeetingRepository meetings,
            SegmentRepository segments,
            SpeechClient speechClient
    ) {
        this.meetings = meetings;
        this.segments = segments;
        this.speechClient = speechClient;
    }

    @Override
    public JobType type() {
        return JobType.TRANSCRIBE;
    }

    @Override
    public SpeechClient.SpeechTranscriptionResult execute(long targetId) {
        Meeting meeting = meetings.findById(targetId)
                .orElseThrow(() -> JobFailure.permanent("MEETING_NOT_FOUND"));

        if (meeting.getFileKey() == null || meeting.getFileKey().isBlank()) {
            throw JobFailure.permanent("FILE_KEY_MISSING");
        }

        try {
            return speechClient.transcribe(meeting.getFileKey());
        } catch (Exception e) {
            throw JobFailure.retryable("TRANSCRIBE_FAILED");
        }
    }

    @Override
    @Transactional
    public void onSuccess(long targetId, SpeechClient.SpeechTranscriptionResult result) {
        Meeting meeting = meetings.findById(targetId)
                .orElseThrow(() -> new IllegalStateException("회의를 찾을 수 없습니다: " + targetId));

        int version = meeting.getTranscriptVersion();
        List<Segment> segmentEntities = new ArrayList<>();
        int seq = 1;

        if (result != null && result.segments() != null) {
            for (SpeechClient.SpeechSegmentItem item : result.segments()) {
                segmentEntities.add(new Segment(
                        targetId,
                        version,
                        seq++,
                        item.speakerLabel(),
                        item.text()
                ));
            }
        }

        segments.saveAll(segmentEntities);
    }

    @Override
    @Transactional
    public void onFinalFailure(long targetId) {
        meetings.findById(targetId).ifPresent(Meeting::markAnalysisFailed);
    }
}