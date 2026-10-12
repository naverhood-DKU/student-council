package kr.dku.councilflow.job;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 하나를 가져와(claim) 처리하고 결과를 기록한다.
 *
 * 1. 가져오기: 짧은 트랜잭션에서 행을 잠그고 RUNNING + 임대 시각을 적은 뒤 바로 커밋한다.
 * 2. 처리: 트랜잭션 밖에서 handler.execute (AI 호출 등). DB 잠금을 오래 쥐고 있지 않는다.
 * 3. 기록: 새 트랜잭션에서 아직 내 작업이 맞는지(version) 확인하고 결과와 상태를 함께 저장한다.
 *    임대가 지나 다른 Worker가 가져갔다면 내 결과는 버린다.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final Map<JobType, JobHandler<?>> handlers = new EnumMap<>(JobType.class);
    private final TransactionTemplate tx;
    private final WorkerProperties properties;
    private final Clock clock;
    private final JobHeartbeat heartbeat;

    JobRunner(JobRepository jobs, List<JobHandler<?>> handlers, PlatformTransactionManager transactionManager,
              WorkerProperties properties, Clock clock, JobHeartbeat heartbeat) {
        this.jobs = jobs;
        handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
        this.heartbeat = heartbeat;
    }

    /** 가져오기 결과. exhausted 면 시도 횟수를 다 써서 이미 FAILED 로 정리한 작업이다. */
    private record Claim(long jobId, JobType type, long targetId, long version, boolean exhausted) {
    }

    /** 처리할 작업이 있었으면 true(하나 처리함), 없으면 false. */
    public boolean runOnce() {
        Claim claim = tx.execute(status -> claimNext());
        if (claim == null) {
            return false;
        }
        if (!claim.exhausted()) {
            process(claim);
        }
        return true;
    }

    private Claim claimNext() {
        Instant now = clock.instant();
        Long id = jobs.lockNextClaimableId(now).orElse(null);
        if (id == null) {
            return null;
        }
        Job job = jobs.findById(id).orElseThrow();
        if (job.getAttempts() >= properties.maxAttempts()) {
            // 임대가 지난 채로 시도 횟수를 다 썼다: 더 붙잡지 않고 실패로 끝낸다.
            job.fail("MAX_ATTEMPTS_EXCEEDED");
            handler(job.getType()).onFinalFailure(job.getTargetId());
            jobs.saveAndFlush(job);
            log.warn("job={} type={} failed: MAX_ATTEMPTS_EXCEEDED", job.getId(), job.getType());
            return new Claim(job.getId(), job.getType(), job.getTargetId(), job.getVersion(), true);
        }
        job.start(now.plus(properties.lease()));
        jobs.saveAndFlush(job);
        return new Claim(job.getId(), job.getType(), job.getTargetId(), job.getVersion(), false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void process(Claim claim) {
        MDC.put("jobId", String.valueOf(claim.jobId()));
        MDC.put("jobVersion", String.valueOf(claim.version()));
        long startedAt = System.nanoTime();
        JobHandler handler = handler(claim.type());
        String outcome = "LEASE_LOST";
        try (var lease = heartbeat.start(claim.jobId(), claim.version())) {
            try {
                Object result = handler.execute(claim.targetId());
                lease.stop();
                if (!lease.lost()) outcome = recordSuccess(claim, handler, result);
            } catch (JobFailure failure) {
                lease.stop();
                if (!lease.lost()) outcome = recordFailure(claim, failure, handler);
            } catch (RuntimeException unexpected) {
                lease.stop();
                log.error("job handler error type={}", unexpected.getClass().getName());
                if (!lease.lost()) outcome = recordFailure(claim, JobFailure.permanent("INTERNAL_ERROR"), handler);
            }
        } finally {
            long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("job type={} attempt finished outcome={} took={}ms", claim.type(), outcome, tookMs);
            MDC.remove("jobId");
            MDC.remove("jobVersion");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String recordSuccess(Claim claim, JobHandler handler, Object result) {
        try {
            return tx.execute(status -> {
                Job job = ownedJob(claim);
                if (job == null) {
                    return "LEASE_LOST";
                }
                handler.onSuccess(claim.targetId(), result);
                job.succeed();
                jobs.saveAndFlush(job);
                return JobStatus.SUCCEEDED.name();
            });
        } catch (JobFailure failure) {
            // onSuccess 안에서 난 실패(예: 결과 검증 실패). 저장은 모두 취소됐으니 실패만 따로 기록한다.
            return recordFailure(claim, failure, handler);
        }
    }

    private String recordFailure(Claim claim, JobFailure failure, JobHandler<?> handler) {
        return tx.execute(status -> {
            Job job = ownedJob(claim);
            if (job == null) {
                return "LEASE_LOST";
            }
            Instant now = clock.instant();
            boolean retry = failure.kind() == JobFailure.Kind.RETRYABLE
                    && job.getAttempts() < properties.maxAttempts();
            if (retry) {
                // 1번째 실패 뒤 retryDelay, 2번째 뒤 2배 … 로 기다렸다가 다시 한다.
                long multiplier = 1L << Math.max(0, job.getAttempts() - 1);
                job.requeue(failure.code(), now.plus(properties.retryDelay().multipliedBy(multiplier)));
            } else if (failure.kind() == JobFailure.Kind.UNKNOWN_OUTCOME) {
                job.failUnknown(failure.code());
                handler.onFinalFailure(job.getTargetId());
            } else {
                job.fail(failure.code());
                handler.onFinalFailure(job.getTargetId());
            }
            jobs.saveAndFlush(job);
            return job.getStatus() + "(" + failure.code() + ")";
        });
    }

    /** 아직 내가 임대한 작업이면 돌려주고, 다른 Worker가 가져갔거나 끝났으면 null. */
    private Job ownedJob(Claim claim) {
        Job job = jobs.lockOwnedRow(claim.jobId()).orElseThrow();
        if (Thread.currentThread().isInterrupted() || job.getStatus() != JobStatus.RUNNING || job.getVersion() != claim.version()
                || job.getLockedUntil() == null || !job.getLockedUntil().isAfter(clock.instant())) {
            log.warn("job={} lease lost; result discarded", claim.jobId());
            return null;
        }
        return job;
    }

    private JobHandler<?> handler(JobType type) {
        JobHandler<?> handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("no handler for " + type);
        }
        return handler;
    }
}
