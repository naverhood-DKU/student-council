package kr.dku.councilflow.meeting;


import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.job.JobView;
import kr.dku.councilflow.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class MeetingController {

    private final MeetingService meetingService;
    private final ObjectStorage objectStorage;
    private final MeetingDeletionService deletionService;

    MeetingController(MeetingService meetingService, ObjectStorage objectStorage,
                      MeetingDeletionService deletionService) {
        this.meetingService = meetingService;
        this.objectStorage = objectStorage;
        this.deletionService = deletionService;
    }

    /** 회의를 지우면 함께 사라지는 것(결정·업무·체크리스트·다른 그룹에 보낸 요청). 회장만 */
    @GetMapping("/api/meetings/{meetingId}/deletion-impact")
    MeetingDeletionService.DeletionImpact deletionImpact(CurrentUser user, @PathVariable long meetingId) {
        return deletionService.impact(user, meetingId);
    }

    /** 회의 완전 삭제(회장만, 되돌릴 수 없음) */
    @DeleteMapping("/api/meetings/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentUser user, @PathVariable long meetingId) {
        deletionService.delete(user, meetingId);
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

   @PostMapping("/api/orgs/{orgId}/meetings/audio/upload-url")
    public ResponseEntity<AudioUploadResponse> getAudioUploadUrl(
            @PathVariable("orgId") Long orgId,
            @RequestBody AudioUploadRequest request,
            CurrentUser user
    ) {
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
        String confirmedBy = String.valueOf(user.id());

        // orgId는 URL의 PathVariable 값을 우선 사용 (request.orgId()가 null인 경우 대비)
        long targetOrgId = (request.orgId() != null && request.orgId() > 0) ? request.orgId() : orgId;

        Meeting savedMeeting = meetingService.createAudioMeeting(
                targetOrgId,
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
    

    @PostMapping("/api/meetings/{meetingId}/transcribe")
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

    public record TranscribeStatusResponse(String status, String transcriptText, String error) {}

    @GetMapping("/api/meetings/{meetingId}/transcribe/status")
    public ResponseEntity<TranscribeStatusResponse> getTranscribeStatus(
            @PathVariable long meetingId,
            CurrentUser user
    ) {
        var detail = meetingService.get(user, meetingId);

        // 발언 개수(segmentCount)가 0보다 크면 전사 완료!
        if (detail.segmentCount() > 0) {
            return ResponseEntity.ok(new TranscribeStatusResponse("COMPLETED", null, null));
        }

        // 아직 0개면 백엔드 Worker가 전사 처리 중인 상태 반환
        return ResponseEntity.ok(new TranscribeStatusResponse("PROCESSING", null, null));
    }
}
