package kr.dku.councilflow.meeting;

import java.util.ArrayList;
import java.util.List;
import kr.dku.councilflow.ai.SpeechClient;
import kr.dku.councilflow.ai.SpeechException;
import kr.dku.councilflow.job.JobFailure;
import kr.dku.councilflow.job.JobHandler;
import kr.dku.councilflow.job.JobType;
import kr.dku.councilflow.storage.ObjectStorage;
import kr.dku.councilflow.storage.StorageUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TranscribeJobHandler implements JobHandler<SpeechClient.SpeechTranscriptionResult> {

    private final MeetingRepository meetings;
    private final SegmentRepository segments;
    private final SpeechClient speechClient;
    private final ObjectStorage storage;

    public TranscribeJobHandler(
            MeetingRepository meetings,
            SegmentRepository segments,
            SpeechClient speechClient,
            ObjectStorage storage
    ) {
        this.meetings = meetings;
        this.segments = segments;
        this.speechClient = speechClient;
        this.storage = storage;
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
            // 브라우저 업로드가 실패했으면 CLOVA 를 부르기 전에 알린다.
            if (!storage.exists(meeting.getFileKey())) {
                throw JobFailure.permanent("SPEECH_AUDIO_MISSING");
            }
            return speechClient.transcribe(meeting.getFileKey());
        } catch (JobFailure e) {
            throw e;
        } catch (StorageUnavailableException e) {
            throw JobFailure.permanent("STORAGE_NOT_CONFIGURED");
        } catch (SpeechException e) {
            // 키 없음·4xx 는 바로 실패, 일시 오류는 다시 시도, 시간 초과는 처리 여부를 몰라 다시 부르지 않는다.
            throw switch (e.kind()) {
                case RETRYABLE -> JobFailure.retryable(e.code());
                case PERMANENT -> JobFailure.permanent(e.code());
                case UNKNOWN_OUTCOME -> JobFailure.unknownOutcome(e.code());
            };
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