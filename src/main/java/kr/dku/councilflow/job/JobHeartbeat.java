package kr.dku.councilflow.job;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** AI 요청·호출 한도 대기와 별개로 임대를 갱신한다. version은 소유권 토큰이다. */
@Component
public class JobHeartbeat {
    private final JobLeaseStore store;
    private final WorkerProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "job-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    JobHeartbeat(JobLeaseStore store, WorkerProperties properties) {
        this.store = store;
        this.properties = properties;
        if (properties.heartbeatInterval().isNegative() || properties.heartbeatInterval().isZero()
                || properties.heartbeatInterval().multipliedBy(3).compareTo(properties.lease()) > 0) {
            throw new IllegalArgumentException("heartbeat interval must be positive and at most lease/3");
        }
    }

    Lease start(long id, long version) {
        Lease lease = new Lease(id, version, Thread.currentThread());
        long interval = properties.heartbeatInterval().toMillis();
        lease.future = scheduler.scheduleWithFixedDelay(lease::renew, interval, interval, TimeUnit.MILLISECONDS);
        return lease;
    }

    @PreDestroy
    void shutdown() { scheduler.shutdownNow(); }

    final class Lease implements AutoCloseable {
        private final long id;
        private final long version;
        private final Thread owner;
        private ScheduledFuture<?> future;
        private boolean stopped;
        private boolean lost;
        private boolean interruptedByLease;

        Lease(long id, long version, Thread owner) { this.id = id; this.version = version; this.owner = owner; }

        private void renew() {
            synchronized (this) { if (stopped || lost) return; }
            boolean renewed = false;
            try {
                renewed = store.renew(id, version, properties.lease());
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(JobHeartbeat.class).warn("job={} heartbeat failed type={}", id, error.getClass().getSimpleName());
            }
            // No lifecycle monitor is held during I/O. Close and this interrupt decision serialize.
            synchronized (this) {
                if (stopped || lost || renewed) return;
                lost = true;
                interruptedByLease = !owner.isInterrupted();
                owner.interrupt();
            }
        }

        synchronized boolean lost() { return lost; }
        synchronized void stop() {
            stopped = true;
            future.cancel(true);
        }
        @Override public synchronized void close() {
            stop();
            if (interruptedByLease && Thread.currentThread() == owner) Thread.interrupted();
        }
    }
}
