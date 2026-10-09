package kr.dku.councilflow.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * CLOVA Studio(HCX-007) 호출. 구현체는 실제(ClovaStudioClient)와 가짜(FakeStudioClient) 두 개다.
 * 실패는 StudioException(종류: 재시도 가능 / 다시 해도 같음 / 처리 여부 모름)으로 알린다.
 */
public interface StudioClient {

    StudioReply chat(StudioRequest request);

    record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** Structured Outputs 요청. schema 는 HCX-007 이 지원하는 JSON Schema 키워드만 써야 한다. */
    record StudioRequest(List<Message> messages, JsonNode schema) {
    }

    /** content 는 모델이 만든 JSON 문자열(아직 검증 전). finishReason 이 length 면 잘렸을 수 있다. */
    record StudioReply(String content, String finishReason) {
    }
}
