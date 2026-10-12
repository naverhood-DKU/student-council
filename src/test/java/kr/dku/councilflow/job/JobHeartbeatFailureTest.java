package kr.dku.councilflow.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JobHeartbeatFailureTest {
    @Test void DB갱신이_실패하면_실행을_중단하고_심박종료후_인터럽트가_남지_않는다() throws Exception {
        JobLeaseStore store=mock(JobLeaseStore.class);
        when(store.renew(anyLong(),anyLong(),any(Duration.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
        var config=new WorkerProperties(false,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(100),3,Duration.ZERO);
        var heartbeat=new JobHeartbeat(store,config);
        try(var lease=heartbeat.start(1,2)) {
            try {Thread.sleep(1500);} catch(InterruptedException expected) {Thread.currentThread().interrupt();}
            assertThat(lease.lost()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {heartbeat.shutdown();}
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
    @Test void 갱신IO가_막혀도_심박을_종료할_수_있다() throws Exception {
        JobLeaseStore store=mock(JobLeaseStore.class);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(store.renew(anyLong(),anyLong(),any(Duration.class))).thenAnswer(call->{
            entered.countDown();
            while(release.getCount()>0) try {release.await();} catch(InterruptedException ignored) {}
            return true;
        });
        var config=new WorkerProperties(false,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(100),3,Duration.ZERO);
        var heartbeat=new JobHeartbeat(store,config);
        var lease=heartbeat.start(1,2);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                pool.submit(lease::stop).get(200,java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {release.countDown();lease.close();heartbeat.shutdown();}
        }
    }
}
