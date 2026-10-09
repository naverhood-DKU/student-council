package kr.dku.councilflow.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * 작업 상태와 허용 전이. 전이 규칙은 여기 한 곳에만 둔다.
 *
 * QUEUED  → RUNNING(Worker가 가져감) / FAILED(시도 횟수 초과)
 * RUNNING → SUCCEEDED / FAILED / FAILED_UNKNOWN
 *         → QUEUED(일시 오류라 나중에 다시)
 *         → RUNNING(임대 시간이 지나 다른 Worker가 다시 가져감)
 *         → FAILED(임대가 지났는데 시도 횟수도 다 씀)
 * SUCCEEDED, FAILED, FAILED_UNKNOWN 은 끝. 다시 하려면 새 작업을 만든다.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 공급자가 처리했는지 알 수 없는 실패(응답 대기 중 시간 초과). 자동으로 다시 부르지 않는다. */
    FAILED_UNKNOWN;

    public Set<JobStatus> next() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, FAILED_UNKNOWN, QUEUED, RUNNING);
            case SUCCEEDED, FAILED, FAILED_UNKNOWN -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canMoveTo(JobStatus target) {
        return next().contains(target);
    }

    public boolean isFinished() {
        return next().isEmpty();
    }
}
