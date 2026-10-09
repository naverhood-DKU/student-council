package kr.dku.councilflow.meeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptSplitterTest {

    @Test
    void 빈_줄은_버리고_이름_콜론_형식은_화자를_떼어_낸다() {
        List<TranscriptSplitter.Line> lines = TranscriptSplitter.split("""
                회장: 회의를 시작합니다.

                기획부장 : 축제 일정 보고드립니다.
                회장 김민수：네.
                화자1: 녹음 전사 형식도 됩니다.
                회장: 3번 안건으로 넘어가죠.
                """);

        assertThat(lines).containsExactly(
                new TranscriptSplitter.Line("회장", "회의를 시작합니다."),
                new TranscriptSplitter.Line("기획부장", "축제 일정 보고드립니다."),
                new TranscriptSplitter.Line("회장 김민수", "네."),
                new TranscriptSplitter.Line("화자1", "녹음 전사 형식도 됩니다."),
                new TranscriptSplitter.Line("회장", "3번 안건으로 넘어가죠."));
    }

    @Test
    void 시각이나_주소의_콜론은_화자로_보지_않는다() {
        List<TranscriptSplitter.Line> lines = TranscriptSplitter.split("""
                18:30에 다시 모입니다.
                오후 6:30에 다시 모입니다.
                https://example.com 에 자료를 올렸습니다.
                """);

        assertThat(lines).extracting(TranscriptSplitter.Line::speaker).containsOnlyNulls();
        assertThat(lines).extracting(TranscriptSplitter.Line::text).containsExactly(
                "18:30에 다시 모입니다.", "오후 6:30에 다시 모입니다.", "https://example.com 에 자료를 올렸습니다.");
    }

    @Test
    void 콜론만_있고_내용이_없으면_한_줄_그대로() {
        assertThat(TranscriptSplitter.split("안건:"))
                .containsExactly(new TranscriptSplitter.Line(null, "안건:"));
    }
}
