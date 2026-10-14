package kr.dku.councilflow.ai;

import java.util.List;

/**
 * 녹음 내용과 상관없이 고정 문장을 돌려주는 데모 전사. 실제 녹음 내용으로 착각하지 않게 문장마다 "(데모 전사)"를 붙인다.
 * 실제 전사는 SPEECH_MODE=clova 로 켠다(AiConfig).
 */
public class FakeSpeechClient implements SpeechClient {

    static final String DEMO_MARK = "(데모 전사) ";

    @Override
    public SpeechTranscriptionResult transcribe(String objectKey) {
        List<SpeechSegmentItem> fakeSegments = List.of(
                new SpeechSegmentItem("화자1", 0, 4500, DEMO_MARK + "2026학년도 학생회비 결산 회의를 시작하겠습니다."),
                new SpeechSegmentItem("화자2", 4600, 9500, DEMO_MARK + "축제 부스 운영비 지출 증빙 서류는 모두 수합되었습니다."),
                new SpeechSegmentItem("화자1", 9600, 15000, DEMO_MARK + "네, 관련 영수증 검토 후 회칙에 따라 승인 절차를 진행하겠습니다.")
        );

        String fullText = String.join("\n", fakeSegments.stream()
                .map(s -> s.speakerLabel() + ": " + s.text())
                .toList());

        return new SpeechTranscriptionResult(fullText, fakeSegments);
    }
}
