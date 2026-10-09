package kr.dku.councilflow.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * app.worker.enabled=true 인 프로세스에서만 job 테이블을 폴링한다.
 * (운영: worker 프로필로 띄운 서버 / 로컬: App 하나가 같이 처리)
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("app.worker.enabled")
class JobPoller {

    private final JobRunner runner;

    JobPoller(JobRunner runner) {
        this.runner = runner;
    }

    /** 쌓인 작업을 다 처리할 때까지 하나씩 가져가고, 없으면 다음 주기까지 쉰다. */
    @Scheduled(fixedDelayString = "${app.worker.poll-interval:1s}")
    void poll() {
        while (runner.runOnce()) {
            // 다음 작업
        }
    }
}
