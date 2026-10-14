package kr.dku.councilflow.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CLOVA Speech 설정. invokeUrl·secretKey 는 환경변수 CLOVA_SPEECH_INVOKE_URL·CLOVA_SPEECH_SECRET_KEY 로만 받는다.
 * invokeUrl 은 도메인 빌더 > 설정 > [연동 정보]의 "CLOVA Speech Invoke URL" 전체(https://clovaspeech-gw.ncloud.com/external/v1/...)다.
 *
 * @param readTimeout 결과를 기다리는 최대 시간(completion=sync). 넘으면 처리 여부를 모르는 실패(FAILED_UNKNOWN)로 남긴다.
 */
@ConfigurationProperties("app.clova.speech")
public record SpeechProperties(
        String invokeUrl,
        String secretKey,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("180s") Duration readTimeout) {
}
