package kr.dku.councilflow.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** app.ai.mode 로 실제/가짜 구현을 고른다. fake-ai 프로필이 mode=fake 로 바꾼다. */
@Configuration(proxyBeanMethods = false)
class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "app.ai.mode", havingValue = "real", matchIfMissing = true)
    StudioClient clovaStudioClient(StudioProperties properties, ObjectMapper objectMapper) {
        return new ClovaStudioClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
    StudioClient fakeStudioClient() {
        return new FakeStudioClient();
    }
}
