package kr.dku.councilflow.meeting;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.job.JobView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class MeetingController {

    private final MeetingService meetingService;

    MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    /** 텍스트 회의록으로 회의 생성(그룹 owner). */
    @PostMapping("/api/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    MeetingService.MeetingDetail create(CurrentUser user, @Valid @RequestBody MeetingService.CreateTextMeeting request) {
        return meetingService.createFromText(user, request);
    }

    /** PDF 회의록 → 글자(multipart: orgId, file). 결과는 회의록 입력칸에 채워서 사람이 확인한다. */
    @PostMapping(value = "/api/meetings/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PdfTranscriptExtractor.Extracted extractText(CurrentUser user, @RequestParam long orgId,
                                                 @RequestParam("file") MultipartFile file) throws IOException {
        return meetingService.extractPdf(user, orgId, file.getBytes());
    }

    @GetMapping("/api/orgs/{orgId}/meetings")
    List<MeetingService.MeetingView> list(CurrentUser user, @PathVariable long orgId) {
        return meetingService.list(user, orgId);
    }

    @GetMapping("/api/meetings/{meetingId}")
    MeetingService.MeetingDetail get(CurrentUser user, @PathVariable long meetingId) {
        return meetingService.get(user, meetingId);
    }

    /** 분석 작업 생성 → { jobId, ... }. 화면은 GET /api/jobs/{jobId} 로 상태를 확인한다. */
    @PostMapping("/api/meetings/{meetingId}/analysis")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AnalysisAccepted analyze(CurrentUser user, @PathVariable long meetingId) {
        JobView job = meetingService.requestAnalysis(user, meetingId);
        return new AnalysisAccepted(job.id(), job);
    }

    record AnalysisAccepted(long jobId, JobView job) {
    }
}
