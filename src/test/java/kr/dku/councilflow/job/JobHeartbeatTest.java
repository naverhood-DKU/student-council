package kr.dku.councilflow.job;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"app.worker.lease=500ms","app.worker.heartbeat-interval=100ms","app.clova.studio.read-timeout=5s"})
class JobHeartbeatTest extends IntegrationTest {
    @Autowired JobRunner runner;
    @Autowired JobRepository jobs;

    @Test void AI응답을_기다리는_동안_심박이_임대를_유지해_다른_Worker가_가져가지_못한다() throws Exception {
        long meeting=createMeeting("ce",orgId("컴퓨터공학과 학생회"),JobRunnerTest.TRANSCRIPT);
        long id=requestAnalysis("ce",meeting);
        var reply=FakeClovaServer.ok(JobRunnerTest.VALID);
        CLOVA.enqueue(new FakeClovaServer.Reply(reply.status(),reply.body(),1500));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->runner.runOnce());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(CLOVA.requests().isEmpty() && System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(CLOVA.requests()).hasSize(1);
            Thread.sleep(650);
            assertThat(runner.runOnce()).isFalse();
            assertThat(result.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(jobs.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
            assertThat(CLOVA.requests()).hasSize(1);
        }
    }
    @Test void 소유권을_잃으면_늦은_AI결과를_버리고_새_Worker만_완료한다() throws Exception {
        long meeting=createMeeting("ce",orgId("컴퓨터공학과 학생회"),JobRunnerTest.TRANSCRIPT);
        long id=requestAnalysis("ce",meeting);
        var reply=FakeClovaServer.ok(JobRunnerTest.VALID);
        CLOVA.enqueue(new FakeClovaServer.Reply(reply.status(),reply.body(),1500));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->runner.runOnce());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(CLOVA.requests().isEmpty() && System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(CLOVA.requests()).hasSize(1);
            jdbc.update("UPDATE job SET version=version+1, locked_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",id);
            assertThat(result.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(pool.submit(()->Thread.currentThread().isInterrupted()).get()).isFalse();
            CLOVA.enqueue(reply);
            assertThat(runner.runOnce()).isTrue();
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(jobs.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
        }
    }
}
