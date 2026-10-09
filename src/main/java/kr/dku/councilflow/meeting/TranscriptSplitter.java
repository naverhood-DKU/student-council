package kr.dku.councilflow.meeting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 텍스트 회의록을 발언 단위로 나눈다. 빈 줄은 버리고 한 줄 = 발언 하나.
 * "이름: 내용" 형식이면 이름을 화자 표시로 떼어 낸다(이름은 20자 이하, 숫자로 시작하지 않음).
 * 콜론 바로 뒤가 숫자("오후 6:30")이거나 "//"("https://...")면 화자로 보지 않는다.
 */
public final class TranscriptSplitter {

    private static final Pattern SPEAKER_LINE =
            Pattern.compile("^([^:：\\d][^:：]{0,19}?)\\s*[:：](?!//)(?!\\d)\\s*(\\S.*)$");

    private TranscriptSplitter() {
    }

    public record Line(String speaker, String text) {
    }

    /** "이름: 내용" 형식의 줄인지. */
    public static boolean isSpeakerLine(String line) {
        return SPEAKER_LINE.matcher(line.strip()).matches();
    }

    public static List<Line> split(String transcript) {
        List<Line> lines = new ArrayList<>();
        for (String raw : transcript.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = SPEAKER_LINE.matcher(line);
            if (matcher.matches()) {
                lines.add(new Line(matcher.group(1).strip(), matcher.group(2).strip()));
            } else {
                lines.add(new Line(null, line));
            }
        }
        return lines;
    }
}
