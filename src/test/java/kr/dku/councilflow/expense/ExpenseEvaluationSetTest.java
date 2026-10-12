package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 평가셋(scripts/eval/expense): 사람이 정답 조항을 정해 둔 지출 사례 9건으로 확인한다.
 * - 정답 조항이 후보에 있고, 나오면 안 되는 조항은 없다.
 * - 규정에서 못 찾는 지출은 NEEDS_CHECK 이며 "문제가 없다는 뜻이 아닙니다"라고 알린다.
 * - 화면에 나오는 후보 조항 원문은 DB 의 Clause.text 와, 그리고 규정 파일의 그 조항 줄과 100% 같다.
 * - 정답 조항을 인용하는 AI 응답이 오면 체크리스트 근거가 그 조항이고 인용 구절이 DB 원문 안에 있다.
 *
 * 규정 그룹을 새로 만들어 다른 테스트의 규정과 섞이지 않게 한다. 같은 사례를 서버에 대해 돌리는 도구는
 * scripts/eval/expense/run.mjs 이다(실제 AI 로 돌리면 모델 품질을 볼 수 있다).
 */
class ExpenseEvaluationSetTest extends ExpenseTestSupport {

    private static final Path DIR = Path.of("scripts", "eval", "expense");
    private static final Pattern ARTICLE_LINE = Pattern.compile("^(제\\d+조).*$");

    @Autowired
    JobRunner jobRunner;

    @Test
    void 평가_사례의_정답_조항이_나오고_원문이_DB와_규정_파일_모두와_같다() throws Exception {
        JsonNode config = JSON.readTree(Files.readString(DIR.resolve("cases.json")));
        String rules = Files.readString(DIR.resolve(config.path("rules").asString())).replace("\r\n", "\n");
        Map<String, String> articleLines = new LinkedHashMap<>();
        for (String line : rules.split("\n")) {
            Matcher matcher = ARTICLE_LINE.matcher(line.strip());
            if (matcher.matches()) {
                articleLines.put(matcher.group(1), line.strip());
            }
        }
        assertThat(articleLines).hasSize(9);

        NewGroup group = newGroup("평가용 학생회", "evalgroup", "evalchair");
        registerRule(group.ownerToken(), group.id(), rules,
                config.path("effectiveDate").asString(), config.path("docVersion").asString());

        List<String> failures = new ArrayList<>();
        int positives = 0;
        for (JsonNode testCase : config.path("cases")) {
            String id = testCase.path("id").asString();
            Long amount = testCase.has("amount") ? testCase.path("amount").asLong() : null;
            String keywords = testCase.has("keywords") ? testCase.path("keywords").asString() : null;
            String created = requestReview(group.ownerToken(), group.id(), testCase.path("item").asString(), amount, keywords);

            List<String> labels = JsonPath.read(created, "$.candidates[*].label");
            List<String> texts = JsonPath.read(created, "$.candidates[*].text");
            List<Integer> clauseIds = JsonPath.read(created, "$.candidates[*].clauseId");
            String status = JsonPath.read(created, "$.status");
            List<String> expectLabels = strings(testCase.path("expectLabels"));
            String expectStatus = testCase.path("expectStatus").asString();

            if (expectStatus.equals("NEEDS_CHECK")) {
                if (!status.equals("NEEDS_CHECK") || !labels.isEmpty()) {
                    failures.add(id + ": 확인 필요여야 하는데 " + status + " " + labels);
                }
                List<String> notices = JsonPath.read(created, "$.notices");
                if (notices.stream().noneMatch(n -> n.contains("문제가 없다는 뜻이 아닙니다"))) {
                    failures.add(id + ": 문제가 없다는 뜻이 아니라는 알림이 없음");
                }
                if (jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE type = 'EXPENSE' AND target_id = ?", Long.class,
                        num(created, "$.id")) != 0L) {
                    failures.add(id + ": 후보가 없는데 AI 작업이 생김");
                }
            } else if (expectStatus.equals("CANDIDATES_FOUND") && !status.equals("CANDIDATES_FOUND")) {
                failures.add(id + ": 후보를 찾아야 하는데 " + status);
            }
            for (String expected : expectLabels) {
                if (!labels.contains(expected)) {
                    failures.add(id + ": 정답 조항 " + expected + " 가 후보에 없음 " + labels);
                }
            }
            for (String forbidden : strings(testCase.path("forbidLabels"))) {
                if (labels.contains(forbidden)) {
                    failures.add(id + ": 나오면 안 되는 조항 " + forbidden + " 가 후보에 있음 " + labels);
                }
            }
            // 화면 원문 = DB 원문 = 규정 파일의 조항 줄
            for (int i = 0; i < clauseIds.size(); i++) {
                String dbText = jdbc.queryForObject("SELECT text FROM clause WHERE id = ?", String.class, clauseIds.get(i));
                if (!texts.get(i).equals(dbText)) {
                    failures.add(id + ": " + labels.get(i) + " 화면 원문이 DB 원문과 다름");
                }
                String fileLine = articleLines.get(labels.get(i));
                if (fileLine != null && !texts.get(i).strip().equals(fileLine)) {
                    failures.add(id + ": " + labels.get(i) + " 원문이 규정 파일과 다름");
                }
            }
            if (created.contains("문제없음")) {
                failures.add(id + ": 결과에 문제없음이 있음");
            }

            // 정답 조항을 인용하는 AI 응답이 오면 체크리스트 근거가 그 조항이어야 한다.
            if (!expectLabels.isEmpty() && labels.contains(expectLabels.get(0))) {
                positives++;
                int index = labels.indexOf(expectLabels.get(0));
                CLOVA.enqueue(FakeClovaServer.ok(ai(
                        List.of(cite("해당 조항의 절차 확인", texts.get(index), "C" + (index + 1))),
                        List.of(), List.of(), List.of(), "")));
                if (!jobRunner.runOnce()) {
                    failures.add(id + ": AI 안내 작업이 없음");
                    continue;
                }
                String shown = viewBody(group.ownerToken(), num(created, "$.id"));
                String checkedLabel = JsonPath.read(shown, "$.checklist[0].clauses[0].label");
                String quote = JsonPath.read(shown, "$.checklist[0].quote");
                if (!checkedLabel.equals(expectLabels.get(0))) {
                    failures.add(id + ": 체크리스트 근거가 " + checkedLabel);
                }
                if (!texts.get(index).contains(quote)) {
                    failures.add(id + ": 인용 구절이 조항 원문에 없음");
                }
            }
        }

        assertThat(positives).isGreaterThanOrEqualTo(6);
        assertThat(failures).as("평가 사례 실패 목록").isEmpty();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> values.add(node.asString()));
        }
        return values;
    }
}
