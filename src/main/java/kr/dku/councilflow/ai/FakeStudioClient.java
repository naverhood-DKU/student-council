package kr.dku.councilflow.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * fake-ai 프로필: CLOVA를 부르지 않고 고정된 모양의 결과를 돌려준다(부하 실험·데모 예비용).
 * 내용은 실제 분석이 아니다.
 *
 * - 회의 분석: 근거 발언 ID는 회의록에 실제로 있는 앞쪽 발언을 쓴다.
 * - 지출 안내: 후보 첫 조항([C1])의 첫 줄을 그대로 인용한다. 서버 검증을 실제로 통과하지만 "(가짜 AI)" 표시가 붙는다.
 */
public class FakeStudioClient implements StudioClient {

    private static final Pattern SEGMENT_ID = Pattern.compile("\\[(S\\d+)]");
    /** 지출 안내 프롬프트의 첫 후보: "[C1] 규정 이름 …" 줄 다음 줄이 조항 원문의 첫 줄이다. */
    private static final Pattern FIRST_CLAUSE = Pattern.compile("(?m)^\\[C1] .*\\R(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public StudioReply chat(StudioRequest request) {
        String lastUser = request.messages().stream()
                .filter(m -> m.role().equals("user"))
                .reduce((first, second) -> second)
                .map(Message::content)
                .orElse("");
        if (lastUser.contains("<지출정보>")) {
            return new StudioReply(expenseGuidance(lastUser), "stop");
        }
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

    private static String expenseGuidance(String prompt) {
        Matcher matcher = FIRST_CLAUSE.matcher(prompt);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> approvals = new ArrayList<>();
        if (matcher.find()) {
            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("text", "(가짜 AI) 첫 번째 후보 조항을 확인하세요");
            approval.put("clause_ids", List.of("C1"));
            approval.put("quote", matcher.group(1).strip());
            approvals.add(approval);
        }
        result.put("required_approvals", approvals);
        result.put("required_evidence", List.of());
        result.put("restrictions", List.of());
        result.put("conflicts", List.of());
        result.put("missing_facts", List.of());
        result.put("guidance", "(가짜 AI) 고정 응답입니다. 실제 분석이 아닙니다. 후보 조항 원문을 직접 확인하세요.");
        return JSON.writeValueAsString(result);
    }
}
