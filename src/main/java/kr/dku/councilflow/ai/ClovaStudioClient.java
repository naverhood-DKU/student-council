package kr.dku.councilflow.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.dku.councilflow.ai.StudioException.Kind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CLOVA Studio Chat Completions v3 호출(비스트리밍).
 *   POST {baseUrl}/v3/chat-completions/{model}
 *   헤더: Authorization: Bearer {키}, X-NCP-CLOVASTUDIO-REQUEST-ID
 *   본문: messages, thinking.effort=none, responseFormat{type:json, schema}, maxCompletionTokens …
 *   성공: status.code == "20000", 결과는 result.message.content(JSON 문자열)
 * 근거: https://api.ncloud-docs.com/docs/clovastudio-chatcompletionsv3-so (2026-10-09 확인)
 *   - Structured Outputs 는 HCX-007 전용, thinking.effort 는 none 이어야 함
 *   - HCX-007 은 maxTokens 대신 maxCompletionTokens
 */
public class ClovaStudioClient implements StudioClient {

    private final StudioProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public ClovaStudioClient(StudioProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public StudioReply chat(StudioRequest request) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new StudioException(Kind.PERMANENT, "AI_KEY_MISSING");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("thinking", Map.of("effort", "none"));
        body.put("responseFormat", Map.of("type", "json", "schema", request.schema()));
        body.put("maxCompletionTokens", properties.maxCompletionTokens());
        body.put("temperature", properties.temperature());
        body.put("topP", 0.8);
        body.put("topK", 0);
        body.put("repetitionPenalty", 1.0);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/v3/chat-completions/" + properties.model()))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("X-NCP-CLOVASTUDIO-REQUEST-ID", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = send(httpRequest);
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new StudioException(Kind.RETRYABLE, "AI_HTTP_" + status);
        }

        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (JacksonException e) {
            throw new StudioException(status >= 400 ? Kind.PERMANENT : Kind.RETRYABLE, "AI_HTTP_" + status + "_BAD_BODY");
        }
        String code = root.path("status").path("code").asString("");
        if (status >= 400 || !"20000".equals(code)) {
            throw new StudioException(Kind.PERMANENT, "AI_HTTP_" + status + (code.isEmpty() ? "" : "_" + code));
        }
        JsonNode result = root.path("result");
        String content = result.path("message").path("content").asString(null);
        if (content == null) {
            throw new StudioException(Kind.PERMANENT, "AI_EMPTY_CONTENT");
        }
        return new StudioReply(content, result.path("finishReason").asString(null));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // 연결 자체가 안 됨: 요청이 공급자에게 닿지 않았다.
            throw new StudioException(Kind.RETRYABLE, "AI_CONNECT_FAILED");
        } catch (HttpTimeoutException e) {
            // 요청은 보냈는데 응답을 못 받음: 공급자가 처리했는지 모른다. 즉시 다시 부르지 않는다.
            throw new StudioException(Kind.UNKNOWN_OUTCOME, "AI_TIMEOUT");
        } catch (IOException e) {
            throw new StudioException(Kind.UNKNOWN_OUTCOME, "AI_IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StudioException(Kind.UNKNOWN_OUTCOME, "AI_INTERRUPTED");
        }
    }
}
