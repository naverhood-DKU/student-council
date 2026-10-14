package kr.dku.councilflow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("prod")
public class ClovaSpeechClient implements SpeechClient {

    @Value("${CLOVA_SPEECH_INVOKE_URL:}")
    private String invokeUrl;

    @Value("${CLOVA_SPEECH_SECRET_KEY:}")
    private String secretKey;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public SpeechTranscriptionResult transcribe(String objectKey) {
        if (invokeUrl.isBlank() || secretKey.isBlank()) {
            throw new RuntimeException("CLOVA_SPEECH_INVOKE_URL 또는 CLOVA_SPEECH_SECRET_KEY가 설정되지 않았습니다.");
        }

        try {
            // CLOVA Speech Object Storage 연동 요청 본문
            Map<String, Object> body = Map.of(
                    "dataKey", objectKey,
                    "language", "ko-KR",
                    "completion", "sync"
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(invokeUrl + "/recognizer/object-storage"))
                    .header("Content-Type", "application/json")
                    .header("X-CLOVASPEECH-API-KEY", secretKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(Duration.ofMinutes(3))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("CLOVA Speech API 호출 실패: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode root = json.readTree(response.body());
            String fullText = root.path("text").asText("");
            List<SpeechSegmentItem> segments = new ArrayList<>();

            for (JsonNode seg : root.path("segments")) {
                String text = seg.path("text").asText();
                int start = seg.path("start").asInt();
                int end = seg.path("end").asInt();
                String speaker = "화자" + seg.path("diarization").path("label").asText("1");
                segments.add(new SpeechSegmentItem(speaker, start, end, text));
            }

            return new SpeechTranscriptionResult(fullText, segments);
        } catch (Exception e) {
            throw new RuntimeException("CLOVA Speech 전사 처리 중 오류", e);
        }
    }
}