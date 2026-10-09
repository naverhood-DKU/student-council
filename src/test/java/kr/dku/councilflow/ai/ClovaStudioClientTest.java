package kr.dku.councilflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import kr.dku.councilflow.ai.StudioClient.Message;
import kr.dku.councilflow.ai.StudioClient.StudioRequest;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 실제 CLOVA 클라이언트가 응답·오류를 어떤 종류로 나누는지. 상대는 테스트 안의 가짜 서버다. */
class ClovaStudioClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeClovaServer SERVER = new FakeClovaServer();
    private static final StudioRequest REQUEST = new StudioRequest(
            List.of(Message.system("s"), Message.user("u")), JSON.readTree("{\"type\":\"object\"}"));

    @AfterEach
    void reset() {
        SERVER.reset();
    }

    private static ClovaStudioClient client(String baseUrl, String key) {
        return new ClovaStudioClient(new StudioProperties(baseUrl, key, "HCX-007", Duration.ofSeconds(1),
                Duration.ofMillis(500), 4096, 0.2), JSON);
    }

    @Test
    void 성공하면_content와_finishReason을_돌려준다() {
        SERVER.enqueue(FakeClovaServer.ok("{\"agendas\":[]}", "length"));

        StudioClient.StudioReply reply = client(SERVER.url(), "k").chat(REQUEST);

        assertThat(reply.content()).isEqualTo("{\"agendas\":[]}");
        assertThat(reply.finishReason()).isEqualTo("length");
        var body = SERVER.requests().get(0).body();
        assertThat(body.path("maxCompletionTokens").asInt()).isEqualTo(4096);
        assertThat(body.path("responseFormat").path("schema").path("type").asString()).isEqualTo("object");
    }

    @Test
    void 키가_없으면_부르지_않고_실패() {
        assertThatThrownBy(() -> client(SERVER.url(), " ").chat(REQUEST))
                .isInstanceOfSatisfying(StudioException.class, e -> {
                    assertThat(e.kind()).isEqualTo(StudioException.Kind.PERMANENT);
                    assertThat(e.code()).isEqualTo("AI_KEY_MISSING");
                });
        assertThat(SERVER.requests()).isEmpty();
    }

    @Test
    void 오류_종류_나누기() {
        SERVER.enqueue(FakeClovaServer.error(503, "50300"));
        assertKind(StudioException.Kind.RETRYABLE, "AI_HTTP_503");

        SERVER.enqueue(FakeClovaServer.error(429, "42901"));
        assertKind(StudioException.Kind.RETRYABLE, "AI_HTTP_429");

        SERVER.enqueue(FakeClovaServer.error(401, "40100"));
        assertKind(StudioException.Kind.PERMANENT, "AI_HTTP_401_40100");

        SERVER.enqueue(FakeClovaServer.error(200, "40001"));
        assertKind(StudioException.Kind.PERMANENT, "AI_HTTP_200_40001");

        SERVER.enqueue(FakeClovaServer.slow(1_500));
        assertKind(StudioException.Kind.UNKNOWN_OUTCOME, "AI_TIMEOUT");
    }

    @Test
    void 연결이_안_되면_재시도_가능() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort, "k").chat(REQUEST))
                .isInstanceOfSatisfying(StudioException.class, e -> {
                    assertThat(e.kind()).isEqualTo(StudioException.Kind.RETRYABLE);
                    assertThat(e.code()).isEqualTo("AI_CONNECT_FAILED");
                });
    }

    private static void assertKind(StudioException.Kind kind, String code) {
        assertThatThrownBy(() -> client(SERVER.url(), "k").chat(REQUEST))
                .isInstanceOfSatisfying(StudioException.class, e -> {
                    assertThat(e.kind()).isEqualTo(kind);
                    assertThat(e.code()).isEqualTo(code);
                });
    }
}
