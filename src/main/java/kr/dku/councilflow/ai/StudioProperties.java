package kr.dku.councilflow.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CLOVA Studio 설정. apiKey 는 환경변수 CLOVA_STUDIO_API_KEY 로만 받는다(브라우저로 보내지 않음).
 *
 * @param readTimeout 응답을 기다리는 최대 시간. 넘으면 처리 여부를 모르는 실패(FAILED_UNKNOWN)로 남긴다.
 *                    Worker 임대 시간(app.worker.lease)보다 짧아야 한다.
 */
@ConfigurationProperties("app.clova.studio")
public record StudioProperties(
        @DefaultValue("https://clovastudio.stream.ntruss.com") String baseUrl,
        String apiKey,
        @DefaultValue("HCX-007") String model,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("240s") Duration readTimeout,
        @DefaultValue("8192") int maxCompletionTokens,
        @DefaultValue("0.2") double temperature) {
}
