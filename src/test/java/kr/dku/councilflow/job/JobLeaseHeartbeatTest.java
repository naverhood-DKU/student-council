package kr.dku.councilflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Real MySQL and a delayed HTTP response exercise renewal while execute() is blocked. */
@TestPropertySource(properties = {"app.worker.lease=2s", "app.clova.studio.read-timeout=6s"})
class JobLeaseHeartbeatTest extends IntegrationTest {
    @Autowired JobRunner runner;
    @Autowired JobRepository jobs;

    @Test
    void 긴_분석은_임대를_갱신해서_다른_Worker가_가져가지_못한다() throws Exception {
        long id = newJob();
        CLOVA.enqueue(delayedReply());
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(runner::runOnce);
        try {
            Job initial = awaitRunning(id);
            Thread.sleep(2_500);
            Job renewed = jobs.findById(id).orElseThrow();
            assertThat(renewed.getLockedUntil()).isAfter(initial.getLockedUntil());
            assertThat(renewed.getVersion()).isEqualTo(initial.getVersion());
            assertThat(runner.runOnce()).isFalse();
            assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            Thread.sleep(900);
            assertThat(jobs.findById(id).orElseThrow().getLockedUntil()).isNull();
        } finally {
            running.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void 소유권이_바뀌면_이전_Worker는_임대와_결과를_덮어쓰지_않는다() throws Exception {
        long id = newJob();
        CLOVA.enqueue(delayedReply());
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(runner::runOnce);
        try {
            awaitRunning(id);
            jdbc.update("UPDATE job SET version = version + 1, locked_until = UTC_TIMESTAMP(6) + INTERVAL 30 SECOND WHERE id = ?", id);
            Instant replacementLease = jobs.findById(id).orElseThrow().getLockedUntil();
            running.get(10, TimeUnit.SECONDS);
            Job current = jobs.findById(id).orElseThrow();
            assertThat(current.getLockedUntil()).isEqualTo(replacementLease);
            assertThat(current.getStatus()).isEqualTo(JobStatus.RUNNING);
        } finally {
            running.get(10, TimeUnit.SECONDS);
        }
    }

    private long newJob() throws Exception {
        return requestAnalysis("ce", createMeeting("ce", orgId("컴퓨터공학과 학생회"), JobRunnerTest.TRANSCRIPT));
    }

    private FakeClovaServer.Reply delayedReply() {
        var reply = FakeClovaServer.ok(JobRunnerTest.VALID);
        return new FakeClovaServer.Reply(reply.status(), reply.body(), 4_500);
    }

    private Job awaitRunning(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Job job = jobs.findById(id).orElseThrow();
            if (job.getStatus() == JobStatus.RUNNING) return job;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Worker did not claim the job");
    }
}
