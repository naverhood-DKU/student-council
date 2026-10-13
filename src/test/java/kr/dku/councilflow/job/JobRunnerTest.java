package kr.dku.councilflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.meeting.MeetingRepository;
import kr.dku.councilflow.meeting.MeetingStatus;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Worker 동작: 재시도, 시도 횟수 한도, 임대 만료, 처리 여부 모름, JSON 깨짐 1회 재요청, SKIP LOCKED. */
class JobRunnerTest extends IntegrationTest {

    static final String TRANSCRIPT = """
            회장: 다음 주 금요일까지 회칙 개정안을 준비하기로 합시다.
            기획부장: 네, 기획부가 준비하겠습니다.
            """;
    static final String VALID = """
            {"agendas":[{"agenda":"회칙 개정","decisions":[{"summary":"회칙 개정안을 준비한다.","decision_status":"CONFIRMED",
              "source_segment_ids":["S1"],"tasks":[{"title":"개정안 초안 작성","owner_department":"기획부","due_date":"","source_segment_ids":["S2"]}]}]}]}
            """;

    @Autowired
    JobRunner runner;

    @Autowired
    JobRepository jobs;

    @Autowired
    MeetingRepository meetings;

    private long newAnalysisJob() throws Exception {
        long meetingId = createMeeting("ce", orgId("컴퓨터공학과 학생회"), TRANSCRIPT);
        return requestAnalysis("ce", meetingId);
    }

    private Job job(long id) {
        return jobs.findById(id).orElseThrow();
    }

    private MeetingStatus meetingStatusOf(long jobId) {
        return meetings.findById(job(jobId).getTargetId()).orElseThrow().getStatus();
    }

    @Test
    void 처리할_작업이_없으면_false() {
        assertThat(runner.runOnce()).isFalse();
    }

    @Test
    void 일시_오류는_다시_대기열로_가고_다음_시도에서_성공한다() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.error(503, "50300"), FakeClovaServer.ok(VALID));

        runner.runOnce();
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job(jobId).getLastError()).isEqualTo("AI_HTTP_503");
        assertThat(job(jobId).getAttempts()).isEqualTo(1);

        runner.runOnce();
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job(jobId).getAttempts()).isEqualTo(2);
        assertThat(meetingStatusOf(jobId)).isEqualTo(MeetingStatus.ANALYZED);
    }

    @Test
    void 일시_오류가_세_번이면_FAILED() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.error(500, "50000"), FakeClovaServer.error(500, "50000"),
                FakeClovaServer.error(429, "42901"));

        runner.runOnce();
        runner.runOnce();
        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job(jobId).getAttempts()).isEqualTo(3);
        assertThat(job(jobId).getLastError()).isEqualTo("AI_HTTP_429");
        assertThat(meetingStatusOf(jobId)).isEqualTo(MeetingStatus.ANALYSIS_FAILED);
        assertThat(runner.runOnce()).isFalse();
    }

    @Test
    void 잘못된_요청은_재시도하지_않는다() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.error(400, "40001"));

        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job(jobId).getLastError()).isEqualTo("AI_HTTP_400_40001");
        assertThat(CLOVA.requests()).hasSize(1);
    }

    @Test
    void 응답_대기_시간초과는_FAILED_UNKNOWN_이고_다시_부르지_않는다() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.slow(2_500));

        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED_UNKNOWN);
        assertThat(job(jobId).getLastError()).isEqualTo("AI_TIMEOUT");
        assertThat(meetingStatusOf(jobId)).isEqualTo(MeetingStatus.ANALYSIS_FAILED);
        assertThat(runner.runOnce()).isFalse();
        assertThat(CLOVA.requests()).hasSize(1);
    }

    @Test
    void JSON이_깨지면_정확히_한_번만_다시_요청한다() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.ok("{\"agendas\": [ 잘린 응답"), FakeClovaServer.ok(VALID));

        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job(jobId).getAttempts()).isEqualTo(1);
        List<FakeClovaServer.Recorded> sent = CLOVA.requests();
        assertThat(sent).hasSize(2);
        // 두 번째 요청에는 첫 응답과 재요청 안내가 붙는다.
        var messages = sent.get(1).body().path("messages");
        assertThat(messages.size()).isEqualTo(4);
        assertThat(messages.get(2).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(3).path("content").asString()).contains("JSON 형식과 맞지 않습니다");
    }

    @Test
    void JSON이_두_번_깨지면_FAILED() throws Exception {
        long jobId = newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.ok("not json"), FakeClovaServer.ok("{\"agendas\": \"wrong\"}"));

        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job(jobId).getLastError()).isEqualTo("AI_INVALID_RESPONSE");
        assertThat(CLOVA.requests()).hasSize(2);
    }

    @Test
    void 임대가_지난_RUNNING_작업은_다시_가져간다() throws Exception {
        long jobId = newAnalysisJob();
        expireLease(jobId, 1);
        CLOVA.enqueue(FakeClovaServer.ok(VALID));

        runner.runOnce();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job(jobId).getAttempts()).isEqualTo(2);
    }

    @Test
    void 임대가_지났는데_시도_횟수도_다_썼으면_FAILED() throws Exception {
        long jobId = newAnalysisJob();
        expireLease(jobId, 3);

        assertThat(runner.runOnce()).isTrue();

        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job(jobId).getLastError()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
        assertThat(meetingStatusOf(jobId)).isEqualTo(MeetingStatus.ANALYSIS_FAILED);
        assertThat(CLOVA.requests()).isEmpty();
    }

    @Test
    void 임대_중인_RUNNING_작업은_건드리지_않는다() throws Exception {
        long jobId = newAnalysisJob();
        jdbc.update("UPDATE job SET status = 'RUNNING', attempts = 1,"
                + " locked_until = UTC_TIMESTAMP(6) + INTERVAL 600 SECOND WHERE id = ?", jobId);

        assertThat(runner.runOnce()).isFalse();
        assertThat(job(jobId).getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void Worker_두_개가_동시에_돌아도_같은_작업을_두_번_처리하지_않는다() throws Exception {
        long first = newAnalysisJob();
        long second = newAnalysisJob();
        FakeClovaServer.Reply slowOk = FakeClovaServer.ok(VALID);
        slowOk = new FakeClovaServer.Reply(slowOk.status(), slowOk.body(), 300);
        CLOVA.enqueue(slowOk, slowOk);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> work = runner::runOnce;
            List<Future<Boolean>> results = pool.invokeAll(List.of(work, work));
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(job(first).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job(second).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job(first).getAttempts()).isEqualTo(1);
        assertThat(job(second).getAttempts()).isEqualTo(1);
        assertThat(CLOVA.requests()).hasSize(2);
    }

    /**
     * Worker가 작업을 잡은 채 죽은 상황을 만든다.
     * 시각은 UTC로 저장하므로(hibernate.jdbc.time_zone=UTC) SQL에서도 UTC_TIMESTAMP 로 맞춘다.
     */
    private void expireLease(long jobId, int attempts) {
        jdbc.update("UPDATE job SET status = 'RUNNING', attempts = ?,"
                + " locked_until = UTC_TIMESTAMP(6) - INTERVAL 60 SECOND WHERE id = ?", attempts, jobId);
    }

    @Test
    void 임대가_만료된_Worker는_늦은_실패도_기록하지_않는다() throws Exception {
        long id=newAnalysisJob();
        CLOVA.enqueue(FakeClovaServer.slow(2_500));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->runner.runOnce());
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
            while(CLOVA.requests().isEmpty() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(CLOVA.requests()).hasSize(1);
            jdbc.update("UPDATE job SET locked_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",id);
            result.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(job(id).getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(meetingStatusOf(id)).isEqualTo(MeetingStatus.ANALYZING);
        }
    }
}
