package kr.dku.councilflow.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import kr.dku.councilflow.common.BaseEntity;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;

/** 규정 문서 한 건(회칙·세칙·안내 자료). 조항은 Clause 에 있다. */
@Entity
@Table(name = "rule_document")
public class RuleDocument extends BaseEntity {

    /**
     * 이 규정을 등록해 쓰는 그룹(V1 컬럼 이름 applies_to_org_id).
     * null 이면 서비스가 시드로 넣은, 모든 그룹이 쓰는 공통 규정이다(학교 감사규정·회칙 등).
     */
    @Column(name = "applies_to_org_id")
    private Long orgId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleSourceType sourceType;

    @Column(nullable = false, length = 200)
    private String issuer;

    @Column(length = 300)
    private String appliesTo;

    private LocalDate effectiveDate;

    @Column(length = 100)
    private String docVersion;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(length = 255)
    private String fileName;

    @Column(length = 64)
    private String sourceSha256;

    @Column(nullable = false, columnDefinition = "longtext")
    private String sourceText;

    @Column(nullable = false)
    private boolean roundtripOk;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleDocStatus status = RuleDocStatus.DRAFT;

    private Long reviewedBy;

    private Instant reviewedAt;

    protected RuleDocument() {
    }

    public RuleDocument(Long orgId, String title, RuleSourceType sourceType, String issuer, String appliesTo,
                        LocalDate effectiveDate, String docVersion, String notes, String fileName,
                        String sourceSha256, String sourceText, boolean roundtripOk) {
        this.orgId = orgId;
        this.title = title;
        this.sourceType = sourceType;
        this.issuer = issuer;
        this.appliesTo = appliesTo;
        this.effectiveDate = effectiveDate;
        this.docVersion = docVersion;
        this.notes = notes;
        this.fileName = fileName;
        this.sourceSha256 = sourceSha256;
        this.sourceText = sourceText;
        this.roundtripOk = roundtripOk;
    }

    /** 확정 전(DRAFT)에만 고칠 수 있다. */
    public void requireDraft() {
        if (status != RuleDocStatus.DRAFT) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "확정했거나 폐기한 규정은 고칠 수 없습니다. 바뀐 규정은 새로 올려 주세요.");
        }
    }

    public void changeMetadata(String title, RuleSourceType sourceType, String issuer, String appliesTo,
                               LocalDate effectiveDate, String docVersion, String notes) {
        requireDraft();
        this.title = title;
        this.sourceType = sourceType;
        this.issuer = issuer;
        this.appliesTo = appliesTo;
        this.effectiveDate = effectiveDate;
        this.docVersion = docVersion;
        this.notes = notes;
    }

    public void confirm(long userId, Instant now) {
        requireDraft();
        this.status = RuleDocStatus.CONFIRMED;
        this.reviewedBy = userId;
        this.reviewedAt = now;
    }

    /**
     * 서비스가 시드로 넣은 공통 규정을 확정한다. 사람의 확정이 아니라 개발 시점에 검수한 값을 넣는 것이라 확정자가 없다.
     */
    public void confirmBySystem(Instant now) {
        requireDraft();
        this.status = RuleDocStatus.CONFIRMED;
        this.reviewedAt = now;
    }

    /** 모든 그룹이 쓰는 공통 규정인가. 공통 규정은 화면에서 고치거나 지울 수 없다. */
    public boolean isGlobal() {
        return orgId == null;
    }

    public void retire() {
        if (status != RuleDocStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "확정한 규정만 폐기할 수 있습니다.");
        }
        this.status = RuleDocStatus.RETIRED;
    }

    public Long getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public RuleSourceType getSourceType() { return sourceType; }
    public String getIssuer() { return issuer; }
    public String getAppliesTo() { return appliesTo; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public String getDocVersion() { return docVersion; }
    public String getNotes() { return notes; }
    public String getFileName() { return fileName; }
    public String getSourceSha256() { return sourceSha256; }
    public String getSourceText() { return sourceText; }
    public boolean isRoundtripOk() { return roundtripOk; }
    public RuleDocStatus getStatus() { return status; }
    public Long getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
}
