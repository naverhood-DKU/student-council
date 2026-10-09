package kr.dku.councilflow.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled      이 프로세스가 job 테이블을 폴링할지(worker 프로필, local 에서 켬)
 * @param pollInterval 일이 없을 때 다시 확인하는 간격
 * @param lease        작업 하나를 붙잡고 있는 최대 시간. 지나면 다른 Worker가 다시 가져간다.
 *                     AI 호출의 응답 타임아웃보다 길어야 한다.
 * @param maxAttempts  시도 횟수 한도. 넘으면 FAILED.
 * @param retryDelay   일시 오류 뒤 다시 시도하기까지 기다리는 기본 시간(시도마다 배로 늘어남)
 */
@ConfigurationProperties("app.worker")
public record WorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30s") Duration retryDelay) {
}
