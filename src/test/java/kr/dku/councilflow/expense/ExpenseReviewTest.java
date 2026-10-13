package kr.dku.councilflow.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 지출 검토 요청 → 후보 조항 찾기.
 * 확정되고 검수된 규정의 조항만 찾고, 못 찾으면 NEEDS_CHECK 이다.
 *
 * FULLTEXT 인덱스는 커밋된 뒤에야 검색에 잡히므로, 규정 올리기·검수·확정·검색 요청은 각각 별도 요청(별도 트랜잭션)으로 한다.
 * 다른 테스트와 DB 를 같이 쓰고 검색은 글자 2개 조각(ngram) 단위라서, 테스트마다 서로 겹치는 조각이 없는 낱말(marker)을 쓴다.
 * 규정의 나머지 문장에도 marker 에 쓰인 조각이 들어가지 않게 한다.
 */
class ExpenseReviewTest extends IntegrationTest {

    private ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    /** 규정을 올려 모든 조항을 검수하고(시행일 포함) 확정까지 한다. confirm=false 면 검수 중으로 둔다. */
    private long registerRule(String ownerToken, long orgId, String text, String effectiveDate, boolean confirm) throws Exception {
        String title = text.substring(0, text.indexOf('\n'));
        MockMultipartFile file = new MockMultipartFile("file", "rule.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        String created = body(mvc.perform(multipart("/api/orgs/{id}/rule-documents", orgId).file(file)
                        .param("title", title).param("issuer", "테스트 발행 주체")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isCreated()));
        long docId = num(created, "$.summary.id");
        if (!confirm) {
            return docId;
        }
        List<Integer> ids = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE' || @.kind == 'EXTRA' || @.kind == 'SUPPLEMENTARY')].id");
        json(post("/api/rule-documents/{id}/clauses/review", docId), ownerToken, Map.of("clauseIds", ids))
                .andExpect(status().isOk());
        String updated = body(json(patch("/api/rule-documents/{id}", docId)
                        .header(HttpHeaders.IF_MATCH, num(created, "$.summary.version")), ownerToken,
                Map.of("effectiveDate", effectiveDate, "docVersion", "테스트 버전"))
                .andExpect(status().isOk()));
        json(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, num(updated, "$.version")),
                ownerToken, Map.of()).andExpect(status().isOk());
        return docId;
    }

    private static String rule(String marker) {
        return String.join("\n",
                "지출 규정 " + marker + System.nanoTime(),
                "제1조(특별지출) " + marker + "에 쓰는 돈은 미리 회장이 승인한다.",
                "제2조(물품) 비품은 구매 전에 재정부 부장의 확인을 받는다.",
                "제3조(행사) 행사 진행비는 계획서를 첨부한다.",
                "제4조(증빙) 모든 지출은 영수증을 첨부한다.");
    }

    @Test
    void 확정된_규정에서_후보_조항을_찾고_원문을_그대로_돌려준다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("해돋이캠프"), "2020-01-01", true);

        String created = body(json(post("/api/expense-reviews"), owner,
                Map.of("orgId", ce, "item", "해돋이캠프", "amount", 300000, "fundSource", "학생회비"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANDIDATES_FOUND")));

        List<String> labels = JsonPath.read(created, "$.candidates[*].label");
        assertThat(labels).contains("제1조");
        String text = JsonPath.read(created, "$.candidates[0].text");
        assertThat(text).isEqualTo("제1조(특별지출) 해돋이캠프에 쓰는 돈은 미리 회장이 승인한다.");
        assertThat(JsonPath.<String>read(created, "$.searchText")).contains("해돋이캠프");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXPENSE_REVIEW_REQUESTED' AND target_id = ?",
                Long.class, num(created, "$.id"))).isEqualTo(1L);

        // 다시 불러와도 같은 후보가 같은 순서로 보인다.
        mvc.perform(get("/api/expense-reviews/{id}", num(created, "$.id")).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].label").value(labels.get(0)));
        mvc.perform(get("/api/orgs/{id}/expense-reviews", ce).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
    }

    @Test
    void 확정하지_않은_규정은_찾지_않고_사람이_확인하도록_알린다() throws Exception {
        long ai = orgId("AI 융합대학 학생회");
        String owner = bearer("ai");
        registerRule(owner, ai, rule("독서모임"), "2020-01-01", false);

        json(post("/api/expense-reviews"), owner, Map.of("orgId", ai, "item", "독서모임"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.notices[?(@ =~ /.*확정된 규정이 없습니다.*/)]").isNotEmpty());
    }

    @Test
    void 관련_조항이_없으면_NEEDS_CHECK_이고_문제없다는_말을_하지_않는다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("물놀이"), "2020-01-01", true);

        String created = body(json(post("/api/expense-reviews"), owner,
                Map.of("orgId", ce, "item", "쿼쿼쿼쿼쿼 퉁퉁퉁퉁"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.candidates").isEmpty()));
        List<String> notices = JsonPath.read(created, "$.notices");
        assertThat(notices).anyMatch(n -> n.contains("문제가 없다는 뜻이 아닙니다"));
    }

    @Test
    void 시행일_전인_규정은_찾지_않고_집행_예정일이_시행일_뒤면_찾는다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("별빛콘서트"), "2099-01-01", true);

        json(post("/api/expense-reviews"), owner, Map.of("orgId", ce, "item", "별빛콘서트"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_CHECK"));
        json(post("/api/expense-reviews"), owner,
                Map.of("orgId", ce, "item", "별빛콘서트", "plannedDate", "2100-01-01"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANDIDATES_FOUND"));
    }

    @Test
    void 부장은_요청할_수_있고_부원과_다른_그룹은_못_한다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");
        registerRule(owner, ce, rule("권한점검"), "2020-01-01", true);
        Map<String, Object> request = Map.of("orgId", ce, "item", "권한점검");

        json(post("/api/expense-reviews"), bearer("ce.plan"), request).andExpect(status().isCreated());
        json(post("/api/expense-reviews"), bearer("ce.member"), request).andExpect(status().isForbidden());
        json(post("/api/expense-reviews"), bearer("sw"), request).andExpect(status().isForbidden());

        String created = body(json(post("/api/expense-reviews"), owner, request).andExpect(status().isCreated()));
        long id = num(created, "$.id");
        mvc.perform(get("/api/expense-reviews/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer("ce.member")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/expense-reviews/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/expense-reviews", ce).header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 항목이_없거나_없는_결정이면_거절한다() throws Exception {
        long ce = orgId("컴퓨터공학과 학생회");
        String owner = bearer("ce");

        json(post("/api/expense-reviews"), owner, Map.of("orgId", ce)).andExpect(status().isBadRequest());
        json(post("/api/expense-reviews"), owner, Map.of("orgId", ce, "item", "가")).andExpect(status().isBadRequest());
        json(post("/api/expense-reviews"), owner, Map.of("orgId", ce, "decisionId", 999999999L, "item", "회식비"))
                .andExpect(status().isNotFound());
        json(post("/api/expense-reviews"), owner, Map.of("orgId", ce, "item", "회식비", "amount", -1))
                .andExpect(status().isBadRequest());
    }
}
