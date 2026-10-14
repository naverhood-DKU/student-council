package kr.dku.councilflow.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.org.Organization;

@Entity
@Table(name = "meeting")
public class Meeting extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private Instant heldAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SourceType sourceType;

    @Column(length = 500)
    private String fileKey;

    @Column(nullable = false)
    private int transcriptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MeetingStatus status;

    @Column(length = 100)
    private String consentConfirmedBy;

    private Instant consentConfirmedAt;

    protected Meeting() {
    }

    public static Meeting fromText(Organization organization, String title, Instant heldAt) {
        Meeting meeting = new Meeting();
        meeting.organization = organization;
        meeting.title = title;
        meeting.heldAt = heldAt;
        meeting.sourceType = SourceType.TEXT;
        meeting.transcriptVersion = 1;
        meeting.status = MeetingStatus.READY;
        return meeting;
    }

        public static Meeting fromAudio(
            Organization organization,
            String title,
            Instant heldAt,
            String fileKey,
            String confirmedBy,
            Instant confirmedAt
    ) {
        Meeting meeting = new Meeting();
        meeting.organization = organization;
        meeting.title = title;
        meeting.heldAt = heldAt;
        meeting.sourceType = SourceType.AUDIO;
        meeting.fileKey = fileKey;
        meeting.transcriptVersion = 1;
        meeting.status = MeetingStatus.READY;
        meeting.consentConfirmedBy = confirmedBy;
        meeting.consentConfirmedAt = confirmedAt;
        return meeting;
    }
    

    public void markAnalyzing() {
        this.status = MeetingStatus.ANALYZING;
    }

    public void markAnalyzed() {
        this.status = MeetingStatus.ANALYZED;
    }

    public void markAnalysisFailed() {
        this.status = MeetingStatus.ANALYSIS_FAILED;
    }

    public void setTranscriptVersion(int transcriptVersion) {
        this.transcriptVersion = transcriptVersion;
    }

    public Organization getOrganization() {
        return organization;
    }

    public long getOrgId() {
        return organization.getId();
    }

    public String getTitle() {
        return title;
    }

    public Instant getHeldAt() {
        return heldAt;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getFileKey() {
        return fileKey;
    }

    public int getTranscriptVersion() {
        return transcriptVersion;
    }

    public MeetingStatus getStatus() {
        return status;
    }

    public void markReady() {
        this.status = MeetingStatus.READY;
    }
}
