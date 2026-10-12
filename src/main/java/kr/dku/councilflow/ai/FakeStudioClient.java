package kr.dku.councilflow.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fake-ai 프로필: CLOVA를 부르지 않고 고정된 모양의 회의 분석 결과를 돌려준다(부하 실험·데모 예비용).
 * 근거 발언 ID는 회의록에 실제로 있는 앞쪽 발언을 쓴다. 내용은 실제 분석이 아니다.
 */
public class FakeStudioClient implements StudioClient {

    private static final Pattern SEGMENT_ID = Pattern.compile("\\[(S\\d+)]");

    @Override
    public StudioReply chat(StudioRequest request) {
        String lastUser = request.messages().stream()
                .filter(m -> m.role().equals("user"))
                .reduce((first, second) -> second)
                .map(Message::content)
                .orElse("");
        List<String> ids = new ArrayList<>();
        Matcher matcher = SEGMENT_ID.matcher(lastUser);
        while (matcher.find() && ids.size() < 3) {
            ids.add(matcher.group(1));
        }
        String first = ids.isEmpty() ? "S1" : ids.get(0);
        String last = ids.isEmpty() ? "S1" : ids.get(ids.size() - 1);
        String content = """
                {"agendas":[{"agenda":"(가짜 AI) 회의 안건","decisions":[{
                  "summary":"(가짜 AI) 고정 응답으로 만든 결정 후보입니다.",
                  "decision_status":"CONFIRMED",
                  "source_segment_ids":["%s"],
                  "tasks":[{"title":"(가짜 AI) 후속 업무","owner_department":"","due_date":"","source_segment_ids":["%s"]}]
                }]}]}
                """.formatted(first, last);
        return new StudioReply(content, "stop");
    }
}
