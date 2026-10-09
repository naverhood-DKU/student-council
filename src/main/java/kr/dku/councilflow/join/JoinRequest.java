package kr.dku.councilflow.join;

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
import kr.dku.councilflow.org.Organization;
import kr.dku.councilflow.user.AppUser;

/** 그룹 가입 신청. 한 사람·한 그룹에 한 줄(DB 고유 제약)이고, 다시 신청하면 같은 줄을 PENDING 으로 되돌린다. */
@Entity
@Table(name = "join_request")
public class JoinRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JoinRequestStatus status = JoinRequestStatus.PENDING;

    private Long decidedBy;

    private Instant decidedAt;

    @Column(length = 500)
    private String rejectReason;

    protected JoinRequest() {
    }

    public JoinRequest(AppUser user, Organization organization) {
        this.user = user;
        this.organization = organization;
    }

    public void approve(long ownerUserId, Instant at) {
        moveTo(JoinRequestStatus.APPROVED);
        this.decidedBy = ownerUserId;
        this.decidedAt = at;
        this.rejectReason = null;
    }

    public void reject(long ownerUserId, Instant at, String reason) {
        moveTo(JoinRequestStatus.REJECTED);
        this.decidedBy = ownerUserId;
        this.decidedAt = at;
        this.rejectReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    /** 다시 신청: 지난 결정은 지운다. */
    public void reopen() {
        moveTo(JoinRequestStatus.PENDING);
        this.decidedBy = null;
        this.decidedAt = null;
        this.rejectReason = null;
    }

    private void moveTo(JoinRequestStatus target) {
        if (!status.canMoveTo(target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, status == JoinRequestStatus.PENDING
                    ? "아직 처리하지 않은 신청입니다."
                    : "이미 처리한 신청입니다.");
        }
        this.status = target;
    }

    public AppUser getUser() {
        return user;
    }

    public Organization getOrganization() {
        return organization;
    }

    public JoinRequestStatus getStatus() {
        return status;
    }

    public String getRejectReason() {
        return rejectReason;
    }
}
