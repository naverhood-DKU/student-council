package kr.dku.councilflow.expense;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 지출 안내 통합 테스트들이 같이 쓰는 도우미: 규정 올려 확정하기, 지출 검토 요청, 가짜 AI 응답 만들기.
 *
 * FULLTEXT 인덱스는 커밋된 뒤에야 검색에 잡히므로, 규정 올리기·검수·확정·검색 요청은 각각 별도 요청(별도 트랜잭션)으로 한다.
 */
abstract class ExpenseTestSupport extends IntegrationTest {

    protected ResultActions json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    protected static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    protected static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    /** 규정(첫 줄이 제목)을 올려 모든 조항을 검수하고 확정한다. */
    protected long registerRule(String ownerToken, long orgId, String text) throws Exception {
        return registerRule(ownerToken, orgId, text, "2020-01-01", "테스트 버전");
    }

    protected long registerRule(String ownerToken, long orgId, String text, String effectiveDate, String docVersion)
            throws Exception {
        String title = text.substring(0, text.indexOf('\n'));
        MockMultipartFile file = new MockMultipartFile("file", "rule.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
        String created = body(mvc.perform(multipart("/api/orgs/{id}/rule-documents", orgId).file(file)
                        .param("title", title).param("issuer", "테스트 발행 주체")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isCreated()));
        long docId = num(created, "$.summary.id");
        List<Integer> ids = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE' || @.kind == 'EXTRA' || @.kind == 'SUPPLEMENTARY')].id");
        json(post("/api/rule-documents/{id}/clauses/review", docId), ownerToken, Map.of("clauseIds", ids))
                .andExpect(status().isOk());
        String updated = body(json(patch("/api/rule-documents/{id}", docId)
                        .header(HttpHeaders.IF_MATCH, num(created, "$.summary.version")), ownerToken,
                Map.of("effectiveDate", effectiveDate, "docVersion", docVersion))
                .andExpect(status().isOk()));
        json(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, num(updated, "$.version")),
                ownerToken, Map.of()).andExpect(status().isOk());
        return docId;
    }

    protected record NewGroup(long id, String ownerToken) {
    }

    /** 확정된 규정이 하나도 없는 새 그룹과 그 회장 계정을 만든다(다른 테스트의 규정과 섞이지 않는다). */
    protected NewGroup newGroup(String name, String joinCode, String loginId) throws Exception {
        String admin = bearer("admin");
        String group = body(json(post("/api/admin/groups"), admin,
                Map.of("name", name, "joinCode", joinCode, "type", "COLLEGE", "parentId", orgId("총학생회"), "term", "2026"))
                .andExpect(status().isCreated()));
        long groupId = num(group, "$.id");
        json(post("/api/admin/groups/{id}/owners", groupId), admin,
                Map.of("loginId", loginId, "name", name + " 회장", "initialPassword", "first-pass-1"))
                .andExpect(status().isOk());
        String login = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + loginId + "\",\"password\":\"first-pass-1\"}")).andExpect(status().isOk()));
        return new NewGroup(groupId, "Bearer " + JsonPath.read(login, "$.accessToken"));
    }

    /** 지출 검토를 요청한다(201). 요청 결과(JSON)를 돌려준다. */
    protected String requestReview(String token, long orgId, String item, Long amount) throws Exception {
        return requestReview(token, orgId, item, amount, null);
    }

    protected String requestReview(String token, long orgId, String item, Long amount, String keywords) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orgId", orgId);
        request.put("item", item);
        if (amount != null) {
            request.put("amount", amount);
        }
        if (keywords != null) {
            request.put("keywords", keywords);
        }
        return body(json(post("/api/expense-reviews"), token, request).andExpect(status().isCreated()));
    }

    protected ResultActions view(String token, long reviewId) throws Exception {
        return mvc.perform(get("/api/expense-reviews/{id}", reviewId).header(HttpHeaders.AUTHORIZATION, token));
    }

    protected String viewBody(String token, long reviewId) throws Exception {
        return body(view(token, reviewId).andExpect(status().isOk()));
    }

    protected static String candidateText(String reviewJson, int index) {
        return JsonPath.read(reviewJson, "$.candidates[" + index + "].text");
    }

    // ---- 가짜 CLOVA 응답 만들기 ----

    protected static Map<String, Object> cite(String text, String quote, String... names) {
        Map<String, Object> cited = new LinkedHashMap<>();
        cited.put("text", text);
        cited.put("clause_ids", List.of(names));
        cited.put("quote", quote);
        return cited;
    }

    protected static Map<String, Object> conflict(String description, String... names) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("description", description);
        conflict.put("clause_ids", List.of(names));
        return conflict;
    }

    protected static String ai(List<?> approvals, List<?> evidence, List<?> conflicts, List<String> missing, String guidance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("required_approvals", approvals);
        result.put("required_evidence", evidence);
        result.put("conflicts", conflicts);
        result.put("missing_facts", missing);
        result.put("guidance", guidance);
        return JSON.writeValueAsString(result);
    }

    /** 후보 조항 C1 에 승인 항목 하나가 근거와 함께 있는 정상 응답 */
    protected static String oneApproval(String reviewJson) {
        return ai(List.of(cite("회장의 사전 승인", candidateText(reviewJson, 0), "C1")), List.of(), List.of(), List.of(),
                "후보 조항을 직접 확인하세요.");
    }
}
