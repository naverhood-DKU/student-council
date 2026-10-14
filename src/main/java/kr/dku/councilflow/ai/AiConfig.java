package kr.dku.councilflow.ai;

import kr.dku.councilflow.storage.LocalAudioFiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * app.ai.mode 로 CLOVA Studio 실제/가짜 구현을, app.speech.mode 로 CLOVA Speech 실제/가짜 구현을 고른다.
 * fake-ai 프로필은 둘 다 fake 로 바꾼다. 녹음 전사는 기본이 fake(데모 전사)이고, SPEECH_MODE=clova 일 때만 실제로 부른다.
 */
@Configuration(proxyBeanMethods = false)
class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "app.ai.mode", havingValue = "real", matchIfMissing = true)
    StudioClient clovaStudioClient(StudioProperties properties, ObjectMapper objectMapper,
                                  StudioQuotaProperties limits, StudioQuota quota) {
        return new ClovaStudioClient(properties, objectMapper, limits.enabled() ? quota : null);
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
    StudioClient fakeStudioClient() {
        return new FakeStudioClient();
    }

    @Bean
    @ConditionalOnProperty(name = "app.speech.mode", havingValue = "clova")
    SpeechClient clovaSpeechClient(SpeechProperties properties, ObjectMapper objectMapper,
                                   ObjectProvider<LocalAudioFiles> localFiles) {
        // 로컬 저장소가 있으면 파일을 직접 올리고, 없으면(운영) Object Storage 연동으로 부른다.
        return new ClovaSpeechClient(properties, objectMapper, localFiles.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "app.speech.mode", havingValue = "fake", matchIfMissing = true)
    SpeechClient fakeSpeechClient() {
        return new FakeSpeechClient();
    }
}
