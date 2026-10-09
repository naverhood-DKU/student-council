package kr.dku.councilflow.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 발언 한 줄. 전사 버전마다 새로 쌓고 고치지 않는다.
 * AI에는 "[S{seq}] 발언" 형식으로 넘기고, AI가 인용한 "S{seq}"를 이 행으로 되돌려 찾는다.
 */
@Entity
@Table(name = "segment")
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long meetingId;

    @Column(nullable = false)
    private int transcriptVersion;

    @Column(nullable = false)
    private int seq;

    /** 화자 표시(회의록에 적힌 이름 또는 화자1·화자2). 실명 사용자와 연결하지 않는다. */
    @Column(length = 50)
    private String speakerLabel;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    private Integer startMs;

    private Integer endMs;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Segment() {
    }

    public Segment(Long meetingId, int transcriptVersion, int seq, String speakerLabel, String text) {
        this.meetingId = meetingId;
        this.transcriptVersion = transcriptVersion;
        this.seq = seq;
        this.speakerLabel = speakerLabel;
        this.text = text;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** AI에 보여 주고 AI가 인용하는 발언 ID. 예: S12 */
    public String label() {
        return "S" + seq;
    }

    public Long getId() {
        return id;
    }

    public Long getMeetingId() {
        return meetingId;
    }

    public int getTranscriptVersion() {
        return transcriptVersion;
    }

    public int getSeq() {
        return seq;
    }

    public String getSpeakerLabel() {
        return speakerLabel;
    }

    public String getText() {
        return text;
    }

    public Integer getStartMs() {
        return startMs;
    }

    public Integer getEndMs() {
        return endMs;
    }
}
