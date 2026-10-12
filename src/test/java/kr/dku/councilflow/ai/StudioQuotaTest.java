package kr.dku.councilflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.concurrent.Executors;
import kr.dku.councilflow.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StudioQuotaTest extends IntegrationTest {
    @Autowired StudioQuota quota;

    @Test void 토큰을_예약한_뒤_고갈되면_호출하지_않고_다음_창까지_대기한다() {
        jdbc.update("DELETE FROM studio_quota");
        Instant now=Instant.parse("2026-10-11T09:00:00Z");
        assertThat(quota.reserve(30_000, now)).isNull();
        assertThat(quota.reserve(30_000, now.plusSeconds(1))).isEqualTo(now.plusSeconds(61));
        assertThat(quota.reserve(30_000, now.plusSeconds(61))).isNull();
    }

    @Test void 두_Worker가_같은_예산을_동시에_소비해도_한_건만_예약된다() throws Exception {
        jdbc.update("DELETE FROM studio_quota");
        Instant now=Instant.parse("2026-10-11T09:00:00Z");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->quota.reserve(40_000, now));
            var b=pool.submit(()->quota.reserve(40_000, now));
            assertThat(java.util.Arrays.asList(a.get(),b.get()).stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
        }
    }

    @Test void 외부_429_냉각은_새_예산창과_다른_Worker에도_적용된다() {
        jdbc.update("DELETE FROM studio_quota");
        Instant now=Instant.parse("2026-10-11T09:00:00Z");
        quota.cooldown(now.plusSeconds(125));
        assertThat(quota.reserve(10,now.plusSeconds(80))).isEqualTo(now.plusSeconds(125));
        assertThat(quota.reserve(10,now.plusSeconds(126))).isNull();
    }

    @Test void 기존_예산행을_두_Worker가_100회_경쟁해도_실패와_중복차감이_없다() throws Exception {
        Instant now=Instant.parse("2026-10-11T09:00:00Z");
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<100;round++) {
                jdbc.update("DELETE FROM studio_quota");
                quota.reserve(0,now);
                var start=new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Callable<Instant> attempt=()->{start.await();return quota.reserve(30_000,now);};
                var a=pool.submit(attempt);var b=pool.submit(attempt);
                assertThat(java.util.Arrays.asList(a.get(),b.get()).stream().filter(java.util.Objects::isNull).count())
                    .as("round %s: exactly one reservation",round).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT used_tokens FROM studio_quota",Long.class)).isEqualTo(30_000L);
                assertThat(jdbc.queryForObject("SELECT used_requests FROM studio_quota",Integer.class)).isEqualTo(2);
            }
        }
    }
    @Test void 빈_예산행과_만료창을_각각_100회_경쟁해도_한번만_차감한다() throws Exception {
        Instant now=Instant.parse("2026-10-11T09:00:00Z");
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(boolean expired: new boolean[]{false,true}) for(int round=0;round<100;round++) {
                jdbc.update("DELETE FROM studio_quota");
                if(expired) quota.reserve(40_000,now.minusSeconds(62));
                var barrier=new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Callable<Instant> task=()->{barrier.await();return quota.reserve(30_000,now);};
                var a=pool.submit(task);var b=pool.submit(task);
                assertThat(java.util.Arrays.asList(a.get(),b.get()).stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT used_tokens FROM studio_quota",Long.class)).isEqualTo(30_000L);
                assertThat(jdbc.queryForObject("SELECT used_requests FROM studio_quota",Integer.class)).isEqualTo(1);
            }
        }
    }
    @Test void 냉각과_잔량피드백과_예약의_100회_경쟁에서_차감과_냉각을_잃지_않는다() throws Exception {
        Instant now=Instant.now();
        var headers=java.net.http.HttpHeaders.of(java.util.Map.of(
            "x-ratelimit-remaining-requests",java.util.List.of("59"),
            "x-ratelimit-remaining-tokens",java.util.List.of("59000")),(k,v)->true);
        try(var pool=Executors.newFixedThreadPool(3)) {
            for(int round=0;round<100;round++) {
                jdbc.update("DELETE FROM studio_quota");quota.reserve(0,now);
                var barrier=new java.util.concurrent.CyclicBarrier(3);
                var a=pool.submit(()->{barrier.await();return quota.reserve(30_000,now);});
                var b=pool.submit(()->{barrier.await();quota.cooldown(now.plusSeconds(120));return true;});
                var c=pool.submit(()->{barrier.await();quota.feedback(headers,false);return true;});
                var reserved=a.get();b.get();c.get();
                if(reserved==null) assertThat(jdbc.queryForObject("SELECT used_tokens FROM studio_quota",Long.class)).isBetween(30_000L,31_000L);
                else assertThat(jdbc.queryForObject("SELECT used_tokens FROM studio_quota",Long.class)).isEqualTo(1_000L);
                assertThat(quota.reserve(1,now.plusSeconds(70))).isEqualTo(now.plusSeconds(120));
            }
        }
    }
}
