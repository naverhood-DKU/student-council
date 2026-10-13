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
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ClovaStudioClient.class);

    private final StudioProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final StudioQuota quota;

    public ClovaStudioClient(StudioProperties properties, ObjectMapper json) {
        this(properties,json,null);
    }

    public ClovaStudioClient(StudioProperties properties,ObjectMapper json,StudioQuota quota) {
        this.properties = properties;
        this.json = json;
        this.quota = quota;
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

        int reservedTokens=quota==null?0:countTokens(body)+properties.maxCompletionTokens()+128;
        HttpResponse<String> response;
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(30).toNanos();
        while(true) {
            if(quota!=null) quota.await(reservedTokens);
            response=send(request("/v3/chat-completions/"+properties.model(),body));
            if(quota!=null) quota.feedback(response.headers(),response.statusCode()==429);
            if(response.statusCode()!=429 || quota==null) break;
            if(System.nanoTime()>deadline) throw new StudioException(Kind.PERMANENT,"AI_QUOTA_WAIT_TIMEOUT");
        }
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

    private HttpRequest request(String path,Map<String,Object> body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("X-NCP-CLOVASTUDIO-REQUEST-ID", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

    }

    /** 공식 v3 계산기는 messages와 responseFormat을 함께 계산한다. 원문·응답은 로그에 남기지 않는다. */
    private int countTokens(Map<String,Object> body) {
        Map<String,Object> countBody=Map.of("messages",body.get("messages"),"responseFormat",body.get("responseFormat"));
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(30).toNanos();
        while(true) {
            quota.await(0);
            HttpResponse<String> response=send(request("/v3/api-tools/chat-tokenize/"+properties.model(),countBody));
            if(response.statusCode()==429) {
                quota.feedback(response.headers(),true);
                if(System.nanoTime()>deadline) throw new StudioException(Kind.PERMANENT,"AI_QUOTA_WAIT_TIMEOUT");
                continue;
            }
            if(response.statusCode()>=500) throw new StudioException(Kind.RETRYABLE,"AI_TOKENIZER_UNAVAILABLE");
            JsonNode root;
            try {root=json.readTree(response.body());}
            catch(JacksonException e) {throw new StudioException(Kind.RETRYABLE,"AI_TOKENIZER_BAD_BODY");}
            if(response.statusCode()!=200 || !"20000".equals(root.path("status").path("code").asString("")))
                throw new StudioException(Kind.PERMANENT,"AI_TOKENIZER_REJECTED");
            int count=sumCounts(root.path("result"));
            if(count<=0) throw new StudioException(Kind.PERMANENT,"AI_TOKEN_COUNT_INVALID");
            return count;
        }
    }

    private static int sumCounts(JsonNode node) {
        if(node.isObject()) {
            int count=node.path("count").asInt(0);
            for(JsonNode child:node) if(child.isObject() || child.isArray()) count+=sumCounts(child);
            return count;
        }
        int count=0;if(node.isArray()) for(JsonNode child:node) count+=sumCounts(child);return count;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            long started=System.nanoTime();
            HttpResponse<String> response=http.send(request, HttpResponse.BodyHandlers.ofString());
            String phase=request.uri().getPath().contains("chat-tokenize")?"tokenizer":"chat";
            log.info("studio http phase={} status={} took_ms={}",phase,response.statusCode(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            return response;
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
