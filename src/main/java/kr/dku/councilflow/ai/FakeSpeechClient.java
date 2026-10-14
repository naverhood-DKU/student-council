package kr.dku.councilflow.ai;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class FakeSpeechClient implements SpeechClient {


    @Override
    public SpeechTranscriptionResult transcribe(String objectKey) {
        List<SpeechSegmentItem> fakeSegments = List.of(
                new SpeechSegmentItem("화자1", 0, 4500, "2026학년도 학생회비 결산 회의를 시작하겠습니다."),
                new SpeechSegmentItem("화자2", 4600, 9500, "축제 부스 운영비 지출 증빙 서류는 모두 수합되었습니다."),
                new SpeechSegmentItem("화자1", 9600, 15000, "네, 관련 영수증 검토 후 회칙에 따라 승인 절차를 진행하겠습니다.")
        );

        String fullText = String.join("\n", fakeSegments.stream()
                .map(s -> s.speakerLabel() + ": " + s.text())
                .toList());

        return new SpeechTranscriptionResult(fullText, fakeSegments);
    }
}

