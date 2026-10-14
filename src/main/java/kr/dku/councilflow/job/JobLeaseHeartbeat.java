package kr.dku.councilflow.job;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** A separate thread renews leases even while the polling thread waits for CLOVA. */
@Component
class JobLeaseHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(JobLeaseHeartbeat.class);
    private final JobRepository jobs;
    private final WorkerProperties properties;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;

    JobLeaseHeartbeat(JobRepository jobs, WorkerProperties properties, Clock clock) {
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
        this.executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "job-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    Registration start(long id, long version) {
        Registration registration = new Registration(id, version);
        long interval = Math.max(1, properties.lease().toMillis() / 3);
        registration.future = executor.scheduleWithFixedDelay(registration::renew, interval, interval,
                TimeUnit.MILLISECONDS);
        return registration;
    }

    final class Registration implements AutoCloseable {
        private final long id;
        private final long version;
        private ScheduledFuture<?> future;
        private boolean closed;

        private Registration(long id, long version) {
            this.id = id;
            this.version = version;
        }

        private synchronized void renew() {
            if (closed) return;
            Instant now = clock.instant();
            try {
                if (jobs.renewLease(id, version, now, now.plus(properties.lease())) == 0) {
                    log.warn("job={} heartbeat stopped: claim expired or ownership lost", id);
                    close();
                }
            } catch (RuntimeException failure) {
                // No exception text: JDBC errors can contain credentials or document data.
                log.warn("job={} heartbeat failed type={}", id, failure.getClass().getSimpleName());
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (future != null) future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
