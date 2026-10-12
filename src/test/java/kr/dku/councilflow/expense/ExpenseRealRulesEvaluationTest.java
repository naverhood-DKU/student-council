package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 실제 규정(단국대학교 감사규정·총대의원회 회칙·학생회칙 PDF)으로 하는 평가.
 * scripts/eval/expense/cases.json 의 사례를 쓴다. 정답 조항은 규정 원문을 읽고 정한 것이다.
 *
 * - 후보 검색: 정답 조항이 후보에 있는지(결정적. 실제 PDF 추출·분리·FULLTEXT 를 그대로 쓴다)
 * - 금지·제한: 서버가 확인한 금지 조항이 "금지 가능성"으로 나오고 근거 조항에 금지 표현이 있다고 표시되는지(AI 응답은 가짜)
 * - 금지 조항을 승인으로 잘못 분류한 응답: 서버가 구절만으로 알 수 있으면 금지로 옮기고, 아니면 근거 조항에 금지
 *   표현이 있다는 표시가 붙는지
 *
 * 실제 모델이 얼마나 잘하는지는 이 테스트로 알 수 없다. scripts/eval/expense/run.mjs --wait 로 따로 본다.
 */
class ExpenseRealRulesEvaluationTest extends ExpenseTestSupport {

    private static final Path DIR = Path.of("scripts", "eval", "expense");
    private static final List<String> RESTRICTION_WORDS = List.of("금지", "넘지 못", "사용 불가", "사용이 불가", "제외한다");

    @Autowired
    JobRunner jobRunner;

    private record Setup(NewGroup group, Map<String, String> titles) {
    }

    private Setup registerRealRules(JsonNode config, String joinCode, String loginId) throws Exception {
        NewGroup group = newGroup("실제 규정 평가 학생회 " + joinCode, joinCode, loginId);
        Map<String, String> titles = new LinkedHashMap<>();
        for (JsonNode doc : config.path("documents")) {
            registerRuleFile(group.ownerToken(), group.id(), DIR.resolve(doc.path("file").asString()),
                    doc.path("title").asString(), doc.path("effectiveDate").asString(), doc.path("docVersion").asString());
            titles.put(doc.path("key").asString(), doc.path("title").asString());
        }
        return new Setup(group, titles);
    }

    private void cancelJob(long reviewId) {
        jdbc.update("UPDATE job SET status = 'FAILED', locked_until = NULL WHERE type = 'EXPENSE' AND target_id = ? "
                + "AND status IN ('QUEUED', 'RUNNING')", reviewId);
    }

    private static boolean has(List<String> labels, List<String> docs, String title, String label) {
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equals(label) && docs.get(i).equals(title)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 실제_규정에서_평가_사례의_정답_조항이_후보에_나오고_금지_조항은_금지_가능성으로_보인다() throws Exception {
        JsonNode config = JSON.readTree(Files.readString(DIR.resolve("cases.json")));
        Setup setup = registerRealRules(config, "realrules1", "realchair1");
        String owner = setup.group().ownerToken();
        List<String> failures = new ArrayList<>();
        int restricted = 0;

        for (JsonNode testCase : config.path("cases")) {
            String id = testCase.path("id").asString();
            Long amount = testCase.has("amount") ? testCase.path("amount").asLong() : null;
            String created = requestReview(owner, setup.group().id(), testCase.path("item").asString(), amount);
            long reviewId = num(created, "$.id");
            List<String> labels = JsonPath.read(created, "$.candidates[*].label");
            List<String> docs = JsonPath.read(created, "$.candidates[*].docTitle");
            List<String> texts = JsonPath.read(created, "$.candidates[*].text");

            // 1) 후보 검색: 정답 조항이 후보에 있다.
            for (JsonNode expected : testCase.path("expectCandidates")) {
                String title = setup.titles().get(expected.path("doc").asString());
                String label = expected.path("label").asString();
                if (!has(labels, docs, title, label)) {
                    failures.add(id + ": 정답 조항 " + title + " " + label + " 가 후보에 없음 " + labels);
                }
            }
            if (created.contains("문제없음")) {
                failures.add(id + ": 결과에 문제없음이 있음");
            }

            // 2) 금지·제한: 정답 조항을 인용한 금지 응답이 오면 서버가 금지 가능성으로 보여 준다.
            //    AI 응답을 쓰지 않는 사례의 대기 작업은 정리한다(작업 큐가 선입선출이라 다음 사례의 응답을 가로챌 수 있다).
            if (!testCase.has("expectRestriction")) {
                cancelJob(reviewId);
                continue;
            }
            String kind = testCase.path("expectRestriction").asString();
            if (kind.equals("ANY")) {
                kind = "LIMITED"; // 금지·제한 중 아무거나 맞는 사례: 가짜 응답은 한도·조건으로 만든다
            }
            JsonNode first = testCase.path("expectCandidates").get(0);
            String title = setup.titles().get(first.path("doc").asString());
            int index = -1;
            for (int i = 0; i < labels.size(); i++) {
                if (labels.get(i).equals(first.path("label").asString()) && docs.get(i).equals(title)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                cancelJob(reviewId);
                continue; // 위에서 이미 실패로 기록했다
            }
            String quote = quoteAround(texts.get(index));
            if (quote == null) {
                failures.add(id + ": 정답 조항 원문에서 금지 표현을 찾지 못해 응답을 만들 수 없음");
                cancelJob(reviewId);
                continue;
            }
            CLOVA.enqueue(FakeClovaServer.ok(aiWithRestrictions(List.of(), List.of(),
                    List.of(restrict(kind, testCase.path("item").asString() + "의 사용을 막거나 제한한다", quote, "C" + (index + 1))),
                    List.of(), List.of(), "")));
            if (!jobRunner.runOnce()) {
                failures.add(id + ": AI 안내 작업이 없음");
                continue;
            }
            String shown = viewBody(owner, reviewId);
            String status = JsonPath.read(shown, "$.status");
            String expectedStatus = kind.equals("PROHIBITED") ? "POSSIBLY_RESTRICTED" : "PROCEDURE_FOUND";
            if (!status.equals(expectedStatus)) {
                failures.add(id + ": 상태가 " + status + " (기대 " + expectedStatus + ")");
            }
            Boolean flagged = JsonPath.read(shown, "$.guidance.restrictions[0].clauses[0].mentionsRestriction");
            if (!Boolean.TRUE.equals(flagged)) {
                failures.add(id + ": 근거 조항에 금지 표현이 있다는 표시가 없음");
            }
            restricted++;
        }

        assertThat(restricted).as("금지·제한 사례 수").isGreaterThanOrEqualTo(6);
        assertThat(failures).as("실제 규정 평가 실패 목록").isEmpty();
    }

    @Test
    void 금지_조항을_승인으로_잘못_분류하면_서버가_옮기거나_근거_조항에_금지_표현이_있다고_표시한다() throws Exception {
        JsonNode config = JSON.readTree(Files.readString(DIR.resolve("cases.json")));
        Setup setup = registerRealRules(config, "realrules2", "realchair2");
        String owner = setup.group().ownerToken();
        String auditTitle = setup.titles().get("audit");

        // 1) 목록 조항의 호만 인용해 승인으로 바꾼 경우: 인용 구절에 금지가 없어 금지로 옮길 수는 없지만, 금지 표현이 있는
        //    조항에서 승인을 말하지 않는 구절을 근거로 든 항목이므로 버린다.
        String created = requestReview(owner, setup.group().id(), "학생회 LT 숙박비", 400000L);
        long id = num(created, "$.id");
        List<String> labels = JsonPath.read(created, "$.candidates[*].label");
        List<String> docs = JsonPath.read(created, "$.candidates[*].docTitle");
        List<String> texts = JsonPath.read(created, "$.candidates[*].text");
        int ban = -1;
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equals("제15조의3") && docs.get(i).equals(auditTitle)) {
                ban = i;
            }
        }
        assertThat(ban).as("제15조의3 이 후보에 있어야 한다 " + labels).isGreaterThanOrEqualTo(0);
        assertThat(texts.get(ban)).contains("학생회 LT");
        CLOVA.enqueue(FakeClovaServer.ok(ai(
                List.of(cite("학생회 LT 숙박비 결재", "2. 학생회 LT", "C" + (ban + 1))), List.of(), List.of(), List.of(), "")));
        assertThat(jobRunner.runOnce()).isTrue();
        // 금지 표현이 있는 조항(제15조의3)을 근거로 들었는데 인용 "2. 학생회 LT" 에는 승인을 말하는 낱말이 없으므로
        // 서버가 이 항목을 버린다. 결재가 필요한 것처럼 보이는 체크리스트 항목이 남지 않는다.
        view(owner, id)
                .andExpect(jsonPath("$.checklist", hasSize(0)))
                .andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.guidance.droppedCount").value(1))
                .andExpect(jsonPath("$.guidance.droppedNotes[0]").value(org.hamcrest.Matchers.containsString("승인·증빙 내용이 아닌 구절")))
                .andExpect(jsonPath("$.candidates[" + ban + "].mentionsRestriction").value(true));

        // 2) 한도 조항을 필요한 승인으로 분류한 경우: 구절 자체가 한도라서 서버가 금지·제한으로 옮긴다.
        String cash = requestReview(owner, setup.group().id(), "행사 현금 지출", 100000L);
        long cashId = num(cash, "$.id");
        List<String> cashLabels = JsonPath.read(cash, "$.candidates[*].label");
        List<String> cashTexts = JsonPath.read(cash, "$.candidates[*].text");
        int rule = cashLabels.indexOf("제15조의1");
        assertThat(rule).as("제15조의1 이 후보에 있어야 한다 " + cashLabels).isGreaterThanOrEqualTo(0);
        assertThat(cashTexts.get(rule)).contains("현금사용을 원칙적으로 금지한다");
        CLOVA.enqueue(FakeClovaServer.ok(ai(
                List.of(cite("현금 사용에 대한 승인", "현금사용을 원칙적으로 금지한다", "C" + (rule + 1))),
                List.of(), List.of(), List.of(), "")));
        assertThat(jobRunner.runOnce()).isTrue();
        view(owner, cashId)
                .andExpect(jsonPath("$.checklist", hasSize(0)))
                .andExpect(jsonPath("$.guidance.restrictions[0].kind").value("PROHIBITED"))
                .andExpect(jsonPath("$.guidance.restrictions[0].text").value("현금사용을 원칙적으로 금지한다"))
                .andExpect(jsonPath("$.status").value("POSSIBLY_RESTRICTED"));
    }

    /** 조항 원문에서 금지·제한 낱말 둘레의 실제 구절(원문의 일부)을 뽑는다. 없으면 null. */
    private static String quoteAround(String text) {
        for (String word : RESTRICTION_WORDS) {
            int at = text.indexOf(word);
            if (at >= 0) {
                int from = Math.max(0, at - 18);
                int to = Math.min(text.length(), at + word.length() + 8);
                return text.substring(from, to).strip();
            }
        }
        return null;
    }
}
