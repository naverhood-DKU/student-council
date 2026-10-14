package kr.dku.councilflow.ai;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.dku.councilflow.ai.SpeechException.Kind;
import kr.dku.councilflow.storage.LocalAudioFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CLOVA Speech 장문 인식(공식 문서: api.ncloud-docs.com ai-application-service-clovaspeech-longsentence).
 * - 운영: 녹음이 Object Storage 에 있으므로 POST {invokeUrl}/recognizer/object-storage { dataKey, ... }
 * - 로컬 실제 시험: 녹음이 이 서버 디스크(LocalAudioFiles)에 있으므로 POST {invokeUrl}/recognizer/upload (multipart media, params)
 * 결과는 completion=sync 로 기다린다. 화자는 segments[].diarization.label 을 "화자N"으로만 저장한다(원칙 6, 실명 연결 없음).
 * 오류는 SpeechException 으로 종류를 나눠 던지고, 녹음 내용과 키는 오류 메시지에 넣지 않는다.
 */
public class ClovaSpeechClient implements SpeechClient {

    private final SpeechProperties properties;
    private final ObjectMapper json;
    /** 로컬 저장소일 때만 있다(운영은 null). */
    private final LocalAudioFiles localFiles;
    private final HttpClient http;

    public ClovaSpeechClient(SpeechProperties properties, ObjectMapper json, LocalAudioFiles localFiles) {
        this.properties = properties;
        this.json = json;
        this.localFiles = localFiles;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    @Override
    public SpeechTranscriptionResult transcribe(String objectKey) {
        if (blank(properties.invokeUrl()) || blank(properties.secretKey())) {
            throw new SpeechException(Kind.PERMANENT, "SPEECH_KEY_MISSING");
        }
        String base = properties.invokeUrl().strip().replaceAll("/+$", "");
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            // Invoke URL 칸에 Secret Key 를 넣는 실수를 막는다(값은 메시지에 넣지 않는다).
            throw new SpeechException(Kind.PERMANENT, "SPEECH_URL_INVALID");
        }
        HttpRequest request;
        try {
            request = localFiles != null ? uploadRequest(base, objectKey) : objectStorageRequest(base, objectKey);
        } catch (FileNotFoundException e) {
            throw new SpeechException(Kind.PERMANENT, "SPEECH_AUDIO_MISSING");
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new SpeechException(Kind.RETRYABLE, "SPEECH_CONNECT_FAILED");
        } catch (HttpTimeoutException e) {
            throw new SpeechException(Kind.UNKNOWN_OUTCOME, "SPEECH_TIMEOUT");
        } catch (IOException e) {
            // 보내는 도중 끊기면 공급자가 받았는지 알 수 없다(분석과 같은 규칙).
            throw new SpeechException(Kind.UNKNOWN_OUTCOME, "SPEECH_IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechException(Kind.UNKNOWN_OUTCOME, "SPEECH_INTERRUPTED");
        }
        int status = response.statusCode();
        if (status != 200) {
            boolean retry = status == 429 || status >= 500;
            throw new SpeechException(retry ? Kind.RETRYABLE : Kind.PERMANENT, "SPEECH_HTTP_" + status);
        }
        return parse(response.body());
    }

    /** 로컬 파일을 그대로 보낸다(multipart: params=JSON, media=파일). */
    private HttpRequest uploadRequest(String base, String objectKey) throws FileNotFoundException {
        Path file = localFiles.find(objectKey).orElseThrow(() -> new FileNotFoundException(objectKey));
        String boundary = "councilflow-" + UUID.randomUUID();
        String params = json.writeValueAsString(Map.of(
                "language", "ko-KR",
                "completion", "sync",
                "fullText", true,
                "diarization", Map.of("enable", true)));
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"params\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + params + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + file.getFileName() + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/recognizer/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CLOVASPEECH-API-KEY", properties.secretKey())
                .timeout(properties.readTimeout())
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(head),
                        HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofByteArray(tail)))
                .build();
    }

    /** Object Storage 에 있는 파일을 읽게 한다. dataKey 는 도메인의 인식 대상 경로 기준이다. */
    private HttpRequest objectStorageRequest(String base, String objectKey) {
        String body = json.writeValueAsString(Map.of(
                "dataKey", objectKey,
                "language", "ko-KR",
                "completion", "sync",
                "fullText", true,
                "diarization", Map.of("enable", true)));
        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/recognizer/object-storage"))
                .header("Content-Type", "application/json")
                .header("X-CLOVASPEECH-API-KEY", properties.secretKey())
                .timeout(properties.readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private SpeechTranscriptionResult parse(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw new SpeechException(Kind.PERMANENT, "SPEECH_INVALID_RESPONSE");
        }
        String result = text(root.path("result"));
        if (!result.isEmpty() && !"COMPLETED".equals(result)) {
            throw new SpeechException(Kind.PERMANENT, "SPEECH_NOT_COMPLETED");
        }
        List<SpeechSegmentItem> segments = new ArrayList<>();
        for (JsonNode segment : root.path("segments")) {
            String text = text(segment.path("text")).strip();
            if (text.isEmpty()) {
                continue;
            }
            String label = text(segment.path("diarization").path("label"));
            segments.add(new SpeechSegmentItem("화자" + (label.isEmpty() ? "1" : label),
                    segment.path("start").asInt(), segment.path("end").asInt(), text));
        }
        if (segments.isEmpty()) {
            throw new SpeechException(Kind.PERMANENT, "SPEECH_EMPTY_RESULT");
        }
        return new SpeechTranscriptionResult(text(root.path("text")), segments);
    }

    private static String text(JsonNode node) {
        return node.isString() || node.isNumber() ? node.asString() : "";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
