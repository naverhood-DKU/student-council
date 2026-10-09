package kr.dku.councilflow.delivery;

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
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.user.AppUser;

/**
 * 받는 조직이 낸 결과(링크나 설명). 낸 뒤에는 고치지 않는다. 반려되면 새로 제출한다.
 * 검토(확정·반려)는 보낸 조직이 한다.
 */
@Entity
@Table(name = "submission")
public class Submission extends BaseEntity {

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(length = 1000)
    private String resultLink;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by", nullable = false)
    private AppUser submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus reviewStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(columnDefinition = "text")
    private String reviewComment;

    private Instant reviewedAt;

    protected Submission() {
    }

    Submission(Delivery delivery, String resultLink, String description, AppUser submittedBy) {
        this.delivery = delivery;
        this.resultLink = resultLink;
        this.description = description;
        this.submittedBy = submittedBy;
        this.reviewStatus = ReviewStatus.PENDING;
    }

    void review(boolean approve, String comment, AppUser reviewer, Instant at) {
        if (reviewStatus != ReviewStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "이미 검토한 제출물입니다.");
        }
        this.reviewStatus = approve ? ReviewStatus.APPROVED : ReviewStatus.REJECTED;
        this.reviewComment = comment;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public String getResultLink() {
        return resultLink;
    }

    public String getDescription() {
        return description;
    }

    public AppUser getSubmittedBy() {
        return submittedBy;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public AppUser getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
