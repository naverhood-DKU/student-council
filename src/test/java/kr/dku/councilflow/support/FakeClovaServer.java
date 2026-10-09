package kr.dku.councilflow.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 안에서 띄우는 가짜 CLOVA Studio 서버. 실제 API는 부르지 않는다.
 * 넣어 둔 응답을 차례로 돌려주고, 받은 요청(헤더·본문)을 기록한다.
 */
public class FakeClovaServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Reply(int status, String body, long delayMs) {
    }

    public record Recorded(String path, Map<String, String> headers, JsonNode body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeClovaServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        replies.clear();
        requests.clear();
    }

    public void enqueue(Reply... next) {
        replies.addAll(List.of(next));
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    /** 성공 응답. content 는 모델이 만든 JSON 문자열 자리. */
    public static Reply ok(String content) {
        return ok(content, "stop");
    }

    public static Reply ok(String content, String finishReason) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("finishReason", finishReason);
        result.put("usage", Map.of("promptTokens", 100, "completionTokens", 50, "totalTokens", 150));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", Map.of("code", "20000", "message", "OK"));
        body.put("result", result);
        return new Reply(200, JSON.writeValueAsString(body), 0);
    }

    public static Reply error(int httpStatus, String clovaCode) {
        String body = JSON.writeValueAsString(Map.of("status", Map.of("code", clovaCode, "message", "error")));
        return new Reply(httpStatus, body, 0);
    }

    public static Reply slow(long delayMs) {
        Reply ok = ok("{\"agendas\":[]}");
        return new Reply(ok.status(), ok.body(), delayMs);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        JsonNode body = raw.isEmpty() ? null : JSON.readTree(raw);
        requests.add(new Recorded(exchange.getRequestURI().getPath(), headers, body));

        Reply reply = replies.poll();
        if (reply == null) {
            reply = error(500, "50000");
        }
        try {
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException | InterruptedException e) {
            // 클라이언트가 먼저 끊은 경우(타임아웃 테스트). 무시한다.
        } finally {
            exchange.close();
        }
    }
}
