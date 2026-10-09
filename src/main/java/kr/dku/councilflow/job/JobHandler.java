package kr.dku.councilflow.job;

/**
 * 작업 종류 하나를 처리하는 방법.
 *
 * execute 는 DB 트랜잭션 밖에서 돈다(외부 API 호출처럼 오래 걸리는 일). 실패하면 JobFailure 를 던진다.
 * onSuccess 와 onFinalFailure 는 작업 상태 변경과 같은 트랜잭션 안에서 돈다.
 */
public interface JobHandler<R> {

    JobType type();

    R execute(long targetId);

    void onSuccess(long targetId, R result);

    /** 더 이상 재시도하지 않는 실패로 끝났을 때(FAILED, FAILED_UNKNOWN). */
    void onFinalFailure(long targetId);
}
