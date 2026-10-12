package kr.dku.councilflow.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JobHeartbeatFailureTest {
    @Test void DB갱신이_실패하면_실행을_중단하고_심박종료후_인터럽트가_남지_않는다() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
        var config=new WorkerProperties(false,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(100),3,Duration.ZERO);
        var heartbeat=new JobHeartbeat(jdbc,config,Clock.systemUTC());
        try(var lease=heartbeat.start(1,2)) {
            try {Thread.sleep(1500);} catch(InterruptedException expected) {Thread.currentThread().interrupt();}
            assertThat(lease.lost()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {heartbeat.shutdown();}
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
