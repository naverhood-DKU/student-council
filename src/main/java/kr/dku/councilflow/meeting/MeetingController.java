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
import kr.dku.councilflow.storage.ObjectStorage;
import java.net.URL;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
class MeetingController {

   private final MeetingService meetingService;
    private final ObjectStorage objectStorage;

    MeetingController(MeetingService meetingService, ObjectStorage objectStorage) {
        this.meetingService = meetingService;
        this.objectStorage = objectStorage;
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
    public record AudioUploadRequest(
            Long orgId,
            String title,
            Instant heldAt,
            String filename,
            long fileSizeBytes,
            boolean consentConfirmed
    ) {}

    public record AudioUploadResponse(
            Long meetingId,
            String objectKey,
            String presignedUploadUrl
    ) {}

    @PostMapping("/meetings/audio-upload-url")
    public ResponseEntity<AudioUploadResponse> getAudioUploadUrl(
            @RequestBody AudioUploadRequest request,
            CurrentUser user
    ) 

    {
        // 1. 참석자 녹음 동의 필수 체크
        if (!request.consentConfirmed()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "참석자 전원의 녹음 동의 확인이 필수입니다.");
        }

        // 2. 파일 확장자 검증 (m4a, mp3, wav 허용)
        String extension = "";
        if (request.filename() != null && request.filename().contains(".")) {
            extension = request.filename().substring(request.filename().lastIndexOf(".") + 1).toLowerCase();
        }
        if (!Set.of("m4a", "mp3", "wav").contains(extension)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "지원하지 않는 오디오 형식입니다. (m4a, mp3, wav만 가능)");
        }

        // 3. 파일 용량 제한 (최대 100MB)
        long maxSizeBytes = 100L * 1024 * 1024;
        if (request.fileSizeBytes() > maxSizeBytes) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "파일 크기는 최대 100MB까지 지원됩니다.");
        }

        // 4. 스토리지 객체 키 및 Presigned URL 생성
        String objectKey = "recordings/" + UUID.randomUUID() + "." + extension;
        URL presignedUrl = objectStorage.generatePresignedUploadUrl(objectKey, maxSizeBytes);

        // 5. 회의 생성 (소속 확인 및 오디오 회의 등록)
        Instant heldAt = request.heldAt() != null ? request.heldAt() : Instant.now();
        Instant confirmedAt = Instant.now();
        String confirmedBy = String.valueOf(user.id()); // 또는 user.username() 등 식별자

        Meeting savedMeeting = meetingService.createAudioMeeting(
                request.orgId(),
                request.title(),
                heldAt,
                objectKey,
                confirmedBy,
                confirmedAt
        );
        return ResponseEntity.ok(new AudioUploadResponse(
                savedMeeting.getId(),
                objectKey,
                presignedUrl.toString()
        ));
    }

    @PostMapping("/meetings/{meetingId}/transcribe")
    public ResponseEntity<JobView> requestTranscription(
            @PathVariable long meetingId,
            CurrentUser user
    ) {
        JobView job = meetingService.requestTranscription(user, meetingId);
        return ResponseEntity.ok(job);
    }

    public record UpdateTranscriptRequest(
            List<MeetingService.SegmentUpdateRequest> segments
    ) {}

    @PutMapping("/meetings/{meetingId}/transcript")
    public ResponseEntity<Void> updateTranscript(
            @PathVariable long meetingId,
            @RequestBody UpdateTranscriptRequest request,
            CurrentUser user
    ) {
        meetingService.updateTranscript(user, meetingId, request.segments());
        return ResponseEntity.noContent().build();
    }

    public record FallbackTextRequest(String rawText) {}

    @PostMapping("/meetings/{meetingId}/transcript/manual")
    public ResponseEntity<Void> fallbackToTextInput(
            @PathVariable long meetingId,
            @RequestBody FallbackTextRequest request,
            CurrentUser user
    ) {
        meetingService.fallbackToTextInput(user, meetingId, request.rawText());
        return ResponseEntity.noContent().build();
    }
}
