package kr.dku.councilflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 운영 저장소(NCP Object Storage)가 어떤 주소를 만들고 버킷에 무엇을 묻는지. 상대는 테스트 안의 가짜 S3 서버다. */
class NcpObjectStorageTest {

    private static final FakeS3 S3 = new FakeS3();
    private static final String BUCKET = "council-recordings-test";
    private static final String KEY = "recordings/3f1c2a4e-0000-4000-8000-000000000001.m4a";

    @BeforeEach
    void reset() {
        S3.reset();
    }

    @AfterAll
    static void stop() {
        S3.stop();
    }

    private static NcpObjectStorage storage(String bucket, String accessKey, String secretKey) {
        return new NcpObjectStorage(new NcpStorageProperties(S3.url(), "kr-standard", bucket, accessKey, secretKey,
                Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofSeconds(5)));
    }

    @Test
    void 업로드_주소는_버킷_경로에_서명이_붙은_PUT_주소다() {
        URL url = storage(BUCKET, "access", "secret").generatePresignedUploadUrl(KEY, 100L * 1024 * 1024);

        assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
        assertThat(url.getQuery()).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256", "X-Amz-Expires=900",
                "kr-standard%2Fs3%2Faws4_request", "X-Amz-SignedHeaders=host", "X-Amz-Signature=");
        // 브라우저가 체크섬 헤더를 따로 보내지 않아도 되게(S3 호환 저장소용)
        assertThat(url.getQuery().toLowerCase()).doesNotContain("checksum");
        assertThat(url.getQuery()).doesNotContain("secret");
        assertThat(S3.requests()).isEmpty();
    }

    @Test
    void 파일이_있는지_버킷에_묻는다() {
        NcpObjectStorage storage = storage(BUCKET, "access", "secret");

        S3.enqueue(200);
        assertThat(storage.exists(KEY)).isTrue();
        S3.enqueue(404);
        assertThat(storage.exists(KEY)).isFalse();

        FakeS3.Recorded sent = S3.requests().get(0);
        assertThat(sent.method()).isEqualTo("HEAD");
        assertThat(sent.path()).isEqualTo("/" + BUCKET + "/" + KEY);
        assertThat(sent.authorization()).startsWith("AWS4-HMAC-SHA256 Credential=access/")
                .contains("/kr-standard/s3/aws4_request");
    }

    @Test
    void 버킷_오류는_숨기지_않고_올린다() {
        for (int i = 0; i < 5; i++) {
            S3.enqueue(403);
        }
        assertThatThrownBy(() -> storage(BUCKET, "access", "secret").exists(KEY))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    @Test
    void 설정이_비어_있으면_앱은_뜨고_녹음_기능만_막는다() {
        NcpObjectStorage storage = storage(BUCKET, " ", "secret");

        assertThatThrownBy(() -> storage.generatePresignedUploadUrl(KEY, 1))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> storage.exists(KEY)).isInstanceOf(StorageUnavailableException.class);
        assertThat(S3.requests()).isEmpty();
    }

    /** 받은 요청을 남기고, 넣어 둔 상태 코드를 차례로 돌려주는 가짜 S3. */
    static class FakeS3 {

        record Recorded(String method, String path, String authorization) {
        }

        private final HttpServer server;
        private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        private final List<Recorded> requests = new CopyOnWriteArrayList<>();

        FakeS3() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization")));
                Integer status = statuses.poll();
                exchange.sendResponseHeaders(status == null ? 500 : status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void enqueue(int status) {
            statuses.add(status);
        }

        List<Recorded> requests() {
            return requests;
        }

        void reset() {
            statuses.clear();
            requests.clear();
        }

        void stop() {
            server.stop(0);
        }
    }
}
