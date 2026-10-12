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
}
