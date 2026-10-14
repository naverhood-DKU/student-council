package kr.dku.councilflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import kr.dku.councilflow.ai.SpeechClient.SpeechTranscriptionResult;
import kr.dku.councilflow.storage.LocalAudioFiles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 실제 CLOVA Speech 클라이언트가 어떤 주소로 무엇을 보내고, 응답·오류를 어떻게 나누는지. 상대는 테스트 안의 가짜 서버다. */
class ClovaSpeechClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeSpeechServer SERVER = new FakeSpeechServer();

    static final String RESULT = """
            {"result":"COMPLETED","message":"Succeeded","text":"할로윈 부스 운영하겠습니다. 네 좋습니다.",
             "segments":[
               {"start":0,"end":2100,"text":"할로윈 부스 운영하겠습니다.","diarization":{"label":"1"}},
               {"start":2200,"end":2300,"text":"  ","diarization":{"label":"2"}},
               {"start":2400,"end":3900,"text":"네 좋습니다.","diarization":{"label":"2"}}
             ]}
            """;

    @TempDir
    Path dir;

    @BeforeEach
    void reset() {
        SERVER.reset();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    private static ClovaSpeechClient client(String url, String key, LocalAudioFiles files) {
        return new ClovaSpeechClient(new SpeechProperties(url, key, Duration.ofSeconds(1), Duration.ofMillis(500)),
                JSON, files);
    }

    @Test
    void 로컬_파일은_upload_주소로_파일째_보내고_발언을_나눈다() throws IOException {
        Path audio = Files.write(dir.resolve("a.m4a"), new byte[] {1, 2, 3, 'm', '4', 'a'});
        SERVER.enqueue(200, RESULT, 0);

        SpeechTranscriptionResult result = client(SERVER.url() + "/", "speech-key", key -> Optional.of(audio))
                .transcribe("recordings/x.m4a");

        assertThat(result.segments()).extracting(SpeechClient.SpeechSegmentItem::text)
                .containsExactly("할로윈 부스 운영하겠습니다.", "네 좋습니다.");
        assertThat(result.segments()).extracting(SpeechClient.SpeechSegmentItem::speakerLabel)
                .containsExactly("화자1", "화자2");
        assertThat(result.segments().get(1).startMs()).isEqualTo(2400);
        assertThat(result.segments().get(1).endMs()).isEqualTo(3900);

        FakeSpeechServer.Recorded sent = SERVER.requests().get(0);
        assertThat(sent.path()).isEqualTo("/recognizer/upload");
        assertThat(sent.key()).isEqualTo("speech-key");
        assertThat(sent.contentType()).startsWith("multipart/form-data; boundary=");
        String body = new String(sent.body(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("name=\"params\"", "\"language\":\"ko-KR\"", "\"completion\":\"sync\"",
                "name=\"media\"; filename=\"a.m4a\"", "\u0001\u0002\u0003m4a");
    }

    @Test
    void 로컬_저장소가_없으면_Object_Storage_연동으로_부른다() {
        SERVER.enqueue(200, RESULT, 0);

        client(SERVER.url(), "speech-key", null).transcribe("recordings/x.m4a");

        FakeSpeechServer.Recorded sent = SERVER.requests().get(0);
        assertThat(sent.path()).isEqualTo("/recognizer/object-storage");
        var body = JSON.readTree(sent.body());
        assertThat(body.path("dataKey").asString()).isEqualTo("recordings/x.m4a");
        assertThat(body.path("language").asString()).isEqualTo("ko-KR");
    }

    @Test
    void 키나_주소가_없거나_주소_칸에_키를_넣으면_부르지_않고_실패() {
        assertCode(() -> client(SERVER.url(), " ", null).transcribe("k"), SpeechException.Kind.PERMANENT,
                "SPEECH_KEY_MISSING");
        assertCode(() -> client("", "speech-key", null).transcribe("k"), SpeechException.Kind.PERMANENT,
                "SPEECH_KEY_MISSING");
        assertCode(() -> client("0123456789abcdef", "speech-key", null).transcribe("k"),
                SpeechException.Kind.PERMANENT, "SPEECH_URL_INVALID");
        assertThat(SERVER.requests()).isEmpty();
    }

    @Test
    void 올린_파일이_없으면_부르지_않고_실패() {
        assertCode(() -> client(SERVER.url(), "speech-key", key -> Optional.empty()).transcribe("recordings/x.m4a"),
                SpeechException.Kind.PERMANENT, "SPEECH_AUDIO_MISSING");
        assertThat(SERVER.requests()).isEmpty();
    }

    @Test
    void 오류_종류_나누기() {
        SpeechClient speech = client(SERVER.url(), "speech-key", null);

        SERVER.enqueue(401, "{}", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.PERMANENT, "SPEECH_HTTP_401");
        SERVER.enqueue(429, "{}", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.RETRYABLE, "SPEECH_HTTP_429");
        SERVER.enqueue(503, "{}", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.RETRYABLE, "SPEECH_HTTP_503");
        SERVER.enqueue(200, "{\"result\":\"FAILED\",\"segments\":[]}", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.PERMANENT, "SPEECH_NOT_COMPLETED");
        SERVER.enqueue(200, "{\"result\":\"COMPLETED\",\"segments\":[]}", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.PERMANENT, "SPEECH_EMPTY_RESULT");
        SERVER.enqueue(200, "not json", 0);
        assertCode(() -> speech.transcribe("k"), SpeechException.Kind.PERMANENT, "SPEECH_INVALID_RESPONSE");
    }

    @Test
    void 결과를_기다리다_시간이_넘으면_처리_여부를_모르는_실패() {
        SERVER.enqueue(200, RESULT, 2000);

        assertCode(() -> client(SERVER.url(), "speech-key", null).transcribe("k"),
                SpeechException.Kind.UNKNOWN_OUTCOME, "SPEECH_TIMEOUT");
    }

    @Test
    void 연결이_안_되면_다시_시도할_수_있는_실패() {
        assertCode(() -> client("http://127.0.0.1:1", "speech-key", null).transcribe("k"),
                SpeechException.Kind.RETRYABLE, "SPEECH_CONNECT_FAILED");
    }

    private static void assertCode(Runnable call, SpeechException.Kind kind, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SpeechException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    /** 받은 요청을 바이트 그대로 남기고, 넣어 둔 응답을 차례로 돌려주는 가짜 CLOVA Speech. */
    static class FakeSpeechServer {

        record Reply(int status, String body, long delayMs) {
        }

        record Recorded(String path, String key, String contentType, byte[] body) {
        }

        private final HttpServer server;
        private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
        private final List<Recorded> requests = new CopyOnWriteArrayList<>();

        FakeSpeechServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                requests.add(new Recorded(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-CLOVASPEECH-API-KEY"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
                Reply reply = replies.poll();
                if (reply == null) {
                    reply = new Reply(500, "{}", 0);
                }
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), out.length);
                    exchange.getResponseBody().write(out);
                } catch (IOException ignored) {
                    // 클라이언트가 시간 초과로 먼저 끊은 경우
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void enqueue(int status, String body, long delayMs) {
            replies.add(new Reply(status, body, delayMs));
        }

        List<Recorded> requests() {
            return requests;
        }

        void reset() {
            replies.clear();
            requests.clear();
        }

        void stop() {
            server.stop(0);
        }
    }
}
