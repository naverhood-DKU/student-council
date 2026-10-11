package kr.dku.councilflow.job;

import java.time.Instant;

/** 화면에 보여 줄 작업 상태. lastError 는 오류 종류(예: AI_TIMEOUT)만 담는다. */
public record JobView(long id, JobType type, long targetId, JobStatus status, int attempts, String lastError,
                      Instant createdAt, Instant updatedAt, Instant quotaWaitUntil) {

    public static JobView of(Job job) {
        return new JobView(job.getId(), job.getType(), job.getTargetId(), job.getStatus(), job.getAttempts(),
                job.getLastError(), job.getCreatedAt(), job.getUpdatedAt(), job.getQuotaWaitUntil());
    }
}
