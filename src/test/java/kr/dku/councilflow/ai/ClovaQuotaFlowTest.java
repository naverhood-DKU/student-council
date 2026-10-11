package kr.dku.councilflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import java.util.List;
import java.util.concurrent.Executors;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.job.WorkerProperties;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class ClovaQuotaFlowTest extends IntegrationTest {
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkerProperties worker;
    static class MutableClock extends Clock {
        volatile Instant now=Instant.now();
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    private StudioQuota gate(MutableClock clock) {
        jdbc.update("DELETE FROM studio_quota");
        return new StudioQuota(jdbc,manager,new StudioQuotaProperties(true,"flow-test",60,60000,0.9,Duration.ofMinutes(3)),worker,clock);
    }
    @Test void 토큰과_스키마를_계산하고_429에서_실패하지_않고_냉각_뒤_같은_호출을_완료한다() throws Exception {
        MutableClock clock=new MutableClock();StudioQuota quota=gate(clock);
        var tokenizer=new FakeClovaServer.Reply(200,"""
                {"status":{"code":"20000"},"result":{"messages":[{"content":{"count":100}}],"responseFormat":{"count":50}}}
                """,0);
        CLOVA.enqueue(tokenizer,FakeClovaServer.error(429,"42901"),FakeClovaServer.ok("{\"agendas\":[]}"));
        var client=new ClovaStudioClient(new StudioProperties(CLOVA.url(),"k","HCX-007",Duration.ofSeconds(1),Duration.ofSeconds(1),8192,0.2),JSON,quota);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->client.chat(new StudioClient.StudioRequest(List.of(StudioClient.Message.user("가상 회의")),JSON.readTree("{\"type\":\"object\"}"))));
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(jdbc.queryForObject("SELECT COUNT(*) FROM studio_quota WHERE cooldown_until IS NOT NULL",Integer.class)==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertThat(CLOVA.requests()).hasSize(2);
            assertThat(result.isDone()).isFalse();
            assertThat(CLOVA.requests().get(0).path()).isEqualTo("/v3/api-tools/chat-tokenize/HCX-007");
            assertThat(CLOVA.requests().get(0).body().has("responseFormat")).isTrue();
            clock.now=clock.now.plusSeconds(70);
            assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS).content()).isEqualTo("{\"agendas\":[]}");
            assertThat(CLOVA.requests()).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT used_tokens FROM studio_quota WHERE scope='flow-test'",Long.class)).isEqualTo(150+8192+128);
        }
    }

    @Test void 대기_중_임대를_갱신하고_이전_Worker는_새_소유권을_덮어쓰지_않는다() throws Exception {
        MutableClock clock=new MutableClock();StudioQuota quota=gate(clock);
        long meeting=createMeeting("ce",orgId("컴퓨터공학과 학생회"),"회장: 계획을 작성합니다.");
        long id=requestAnalysis("ce",meeting);
        long version=jdbc.queryForObject("SELECT version FROM job WHERE id=?",Long.class,id);
        jdbc.update("UPDATE job SET status='RUNNING',locked_until=? WHERE id=?",java.sql.Timestamp.from(clock.now.plusSeconds(1)),id);
        assertThat(quota.reserve(40000,clock.now)).isNull();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->{
                org.slf4j.MDC.put("jobId",String.valueOf(id));org.slf4j.MDC.put("jobVersion",String.valueOf(version));
                try{quota.await(40000);}finally{org.slf4j.MDC.clear();}
            });
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(jdbc.queryForObject("SELECT quota_wait_until FROM job WHERE id=?",java.sql.Timestamp.class,id)==null && System.nanoTime()<deadline) Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT quota_wait_until FROM job WHERE id=?",java.sql.Timestamp.class,id)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT locked_until FROM job WHERE id=?",java.sql.Timestamp.class,id).toInstant()).isAfter(clock.now.plusSeconds(500));
            jdbc.update("UPDATE job SET version=version+1 WHERE id=?",id);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(()->result.get(5,java.util.concurrent.TimeUnit.SECONDS))).hasCauseInstanceOf(StudioException.class);
        }
    }
}
