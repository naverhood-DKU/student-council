package kr.dku.councilflow.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 같은 메인 계정·모델·이용 목적의 Worker는 같은 scope를 사용한다. */
@ConfigurationProperties("app.clova.quota")
public record StudioQuotaProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("main-HCX-007-test") String scope,
        @DefaultValue("60") int requestsPerMinute,
        @DefaultValue("60000") int tokensPerMinute,
        @DefaultValue("0.9") double budgetRatio,
        @DefaultValue("30m") Duration maxWait) {
    public StudioQuotaProperties {
        if(scope==null || scope.isBlank() || scope.length()>120 || requestsPerMinute<1
                || tokensPerMinute<1 || budgetRatio<=0 || budgetRatio>1 || maxWait.isNegative() || maxWait.isZero())
            throw new IllegalArgumentException("invalid CLOVA quota configuration");
    }
}
