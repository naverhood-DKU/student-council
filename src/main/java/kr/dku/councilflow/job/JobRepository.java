package kr.dku.councilflow.job;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * 지금 가져갈 수 있는 작업 하나를 잠그고 id를 돌려준다.
     * - QUEUED: 재시도 대기 시각(locked_until)이 없거나 지났으면
     * - RUNNING: 임대 시간(locked_until)이 지났으면(Worker가 죽은 것으로 본다)
     * 다른 Worker가 잠근 행은 건너뛴다(SKIP LOCKED). 반드시 트랜잭션 안에서 부른다.
     *
     * ORDER BY 를 쓰지 않는 이유: 정렬(filesort)이 끼면 MySQL이 조건에 맞는 행을 전부 읽으면서 전부 잠가 버려서,
     * 다른 Worker가 SKIP LOCKED 로 아무것도 못 가져간다. 대신 idx_job_poll(status, locked_until, id) 순서로
     * 읽게 해서 첫 행 하나만 잠그고 멈춘다. 결과 순서는 QUEUED(대기 시각 없음 → 오래된 id 먼저) → RUNNING.
     */
    @Query(value = """
            SELECT id FROM job FORCE INDEX (idx_job_poll)
             WHERE (status = 'QUEUED' AND (locked_until IS NULL OR locked_until <= :now))
                OR (status = 'RUNNING' AND locked_until <= :now)
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockNextClaimableId(Instant now);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.id = :id")
    Optional<Job> lockOwnedRow(long id);

    Optional<Job> findFirstByTypeAndTargetIdOrderByIdDesc(JobType type, Long targetId);

    boolean existsByTypeAndTargetIdAndStatusIn(JobType type, Long targetId, Collection<JobStatus> statuses);
}
