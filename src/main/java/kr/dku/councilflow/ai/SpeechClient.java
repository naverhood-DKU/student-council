package kr.dku.councilflow.ai;

import java.util.List;

public interface SpeechClient {
    SpeechTranscriptionResult transcribe(String objectKey);

    record SpeechTranscriptionResult(
            String fullText,
            List<SpeechSegmentItem> segments
    ) {}

    record SpeechSegmentItem(
            String speakerLabel, // "화자1", "화자2"
            int startMs,
            int endMs,
            String text
    ) {}
}