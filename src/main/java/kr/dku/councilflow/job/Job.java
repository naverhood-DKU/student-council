package kr.dku.councilflow.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.dku.councilflow.common.BaseEntity;

/**
 * DB 작업 큐의 한 줄. Worker가 SELECT ... FOR UPDATE SKIP LOCKED 로 가져가고 locked_until 까지 임대한다.
 * QUEUED 상태에서 locked_until 은 "이 시각 이후에 가져가라"(재시도 대기)는 뜻으로 쓴다.
 */
@Entity
@Table(name = "job")
public class Job extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobType type;

    @Column(nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    private Instant lockedUntil;

    /** 오류 종류만 적는다. 회의 원문이나 모델 응답을 넣지 않는다. */
    @Column(length = 500)
    private String lastError;

    protected Job() {
    }

    public static Job queue(JobType type, Long targetId) {
        Job job = new Job();
        job.type = type;
        job.targetId = targetId;
        job.status = JobStatus.QUEUED;
        return job;
    }

    void start(Instant leaseUntil) {
        moveTo(JobStatus.RUNNING);
        attempts++;
        lockedUntil = leaseUntil;
    }

    void succeed() {
        moveTo(JobStatus.SUCCEEDED);
        lockedUntil = null;
        lastError = null;
    }

    void requeue(String error, Instant notBefore) {
        moveTo(JobStatus.QUEUED);
        lockedUntil = notBefore;
        lastError = error;
    }

    void fail(String error) {
        moveTo(JobStatus.FAILED);
        lockedUntil = null;
        lastError = error;
    }

    void failUnknown(String error) {
        moveTo(JobStatus.FAILED_UNKNOWN);
        lockedUntil = null;
        lastError = error;
    }

    private void moveTo(JobStatus target) {
        if (!status.canMoveTo(target)) {
            throw new IllegalStateException("job " + getId() + ": " + status + " -> " + target);
        }
        status = target;
    }

    public JobType getType() {
        return type;
    }

    public Long getTargetId() {
        return targetId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLastError() {
        return lastError;
    }
}
