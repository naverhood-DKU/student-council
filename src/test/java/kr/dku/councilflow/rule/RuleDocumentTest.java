package kr.dku.councilflow.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 규정 등록 → 조항 분리 → 검수 → 확정. 확정 전에는 근거로 쓰이지 않고,
 * 다른 그룹은 볼 수 없으며, 회장만 바꿀 수 있다.
 */
class RuleDocumentTest extends IntegrationTest {

    private static final String SAMPLE = String.join("\n",
            "테스트 감사규정", "", "제1장 총칙", "",
            "제1조(목적) 이 규정은 감사에 관한 사항을 정한다.", "", "- 2 -", "",
            "제2조(제출) 영수증을 제출한다.", "제3조(삭제) <삭제>", "",
            "부", "", "칙", "이 규정은 공포한 날부터 시행한다.");

    private ResultActions upload(String token, long orgId, String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "rule.txt", "text/plain", SAMPLE.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/orgs/{id}/rule-documents", orgId).file(file)
                .param("title", title).param("issuer", "테스트 발행 주체")
                .header(HttpHeaders.AUTHORIZATION, token));
    }

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

    @Test
    void 회장이_올리면_조항으로_나뉘어_검수_중으로_저장된다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String created = body(upload(bearer("sw"), sw, "테스트 감사규정").andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.status").value("DRAFT"))
                .andExpect(jsonPath("$.summary.articleCount").value(3))
                .andExpect(jsonPath("$.summary.roundtripOk").value(true))
                .andExpect(jsonPath("$.registrationWarnings").isEmpty()));
        List<String> labels = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE')].label");
        assertThat(labels).containsExactly("제1조", "제2조", "제3조");
        List<String> texts = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE')].text");
        assertThat(texts.get(0)).isEqualTo("제1조(목적) 이 규정은 감사에 관한 사항을 정한다.\n");
        assertThat(JsonPath.<String>read(created, "$.sourceSha256")).hasSize(64);
    }

    @Test
    void 부장_부원은_올릴_수_없고_다른_그룹은_볼_수_없다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        upload(bearer("sw.plan"), sw, "부장 시도").andExpect(status().isForbidden());
        upload(bearer("sw.member"), sw, "부원 시도").andExpect(status().isForbidden());
        upload(bearer("ce"), sw, "다른 그룹 시도").andExpect(status().isForbidden());

        long docId = num(body(upload(bearer("sw"), sw, "테스트 감사규정")), "$.summary.id");
        mvc.perform(get("/api/rule-documents/{id}", docId).header(HttpHeaders.AUTHORIZATION, bearer("sw.member")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rule-documents/{id}", docId).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/rule-documents", sw).header(HttpHeaders.AUTHORIZATION, bearer("ce")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조항이_없는_파일과_파일도_글자도_없는_요청은_거절한다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        mvc.perform(multipart("/api/orgs/{id}/rule-documents", sw)
                        .param("title", "빈 규정").param("issuer", "발행").param("text", "조항이 없는 글입니다.")
                        .header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/orgs/{id}/rule-documents", sw)
                        .param("title", "빈 규정").param("issuer", "발행")
                        .header(HttpHeaders.AUTHORIZATION, bearer("sw")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검수와_정보를_채워야_확정되고_확정한_규정은_고칠_수_없다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("sw");
        String created = body(upload(owner, sw, "테스트 감사규정"));
        long docId = num(created, "$.summary.id");
        long docVersion = num(created, "$.summary.version");

        // 시행일·버전이 없으면 확정할 수 없다.
        json(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, docVersion), owner, Map.of())
                .andExpect(status().isBadRequest());

        String updated = body(json(patch("/api/rule-documents/{id}", docId).header(HttpHeaders.IF_MATCH, docVersion), owner,
                Map.of("effectiveDate", "2026-03-01", "docVersion", "2026.03 개정"))
                .andExpect(status().isOk()));
        long version = num(updated, "$.version");

        // 검수하지 않은 조항이 있으면 409
        json(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, version), owner, Map.of())
                .andExpect(status().isConflict());

        // 조항 하나를 고치면 검수 표시가 풀리고, 고쳤다는 표시가 붙는다.
        List<Integer> ids = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE' || @.kind == 'SUPPLEMENTARY')].id");
        List<Integer> versions = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE' || @.kind == 'SUPPLEMENTARY')].version");
        json(patch("/api/clauses/{id}", ids.get(1)).header(HttpHeaders.IF_MATCH, versions.get(1)), owner,
                Map.of("reviewed", true)).andExpect(status().isOk());
        // 낡은 version 으로 고치면 409
        json(patch("/api/clauses/{id}", ids.get(1)).header(HttpHeaders.IF_MATCH, versions.get(1)), owner,
                Map.of("text", "제2조(제출) 영수증을 반드시 제출한다.")).andExpect(status().isConflict());

        // 나머지를 한꺼번에 검수 완료로 표시
        json(post("/api/rule-documents/{id}/clauses/review", docId), owner, Map.of("clauseIds", ids))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewed").value(ids.size()));

        mvc.perform(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, version)
                        .header(HttpHeaders.AUTHORIZATION, bearer("sw.plan")))
                .andExpect(status().isForbidden());
        json(post("/api/rule-documents/{id}/confirm", docId).header(HttpHeaders.IF_MATCH, version), owner, Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 확정한 뒤에는 정보도 조항도 고칠 수 없고 지울 수도 없다. 폐기만 된다.
        json(patch("/api/clauses/{id}", ids.get(0)).header(HttpHeaders.IF_MATCH, versions.get(0)), owner,
                Map.of("text", "바꾼 글")).andExpect(status().isConflict());
        mvc.perform(delete("/api/rule-documents/{id}", docId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'RULE_DOC_CONFIRMED' AND target_id = ?",
                Long.class, docId)).isEqualTo(1L);
    }

    @Test
    void 조항을_직접_추가하고_지우면_기록이_남는다() throws Exception {
        long sw = orgId("소프트웨어학과 학생회");
        String owner = bearer("sw");
        String created = body(upload(owner, sw, "테스트 감사규정"));
        long docId = num(created, "$.summary.id");
        List<Integer> ids = JsonPath.read(created, "$.clauses[?(@.kind == 'ARTICLE')].id");

        String added = body(json(post("/api/rule-documents/{id}/clauses", docId), owner,
                Map.of("afterClauseId", ids.get(0), "label", "제1조의2", "text", "제1조의2(추가) 직접 넣은 조항"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.edited").value(true))
                .andExpect(jsonPath("$.reviewStatus").value("UNREVIEWED")));
        long addedId = num(added, "$.id");

        String detail = body(mvc.perform(get("/api/rule-documents/{id}", docId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk()));
        List<String> labels = JsonPath.read(detail, "$.clauses[?(@.kind == 'ARTICLE')].label");
        assertThat(labels).containsExactly("제1조", "제1조의2", "제2조", "제3조");

        mvc.perform(delete("/api/clauses/{id}", addedId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_type = 'CLAUSE' AND target_id = ?",
                Long.class, addedId)).isEqualTo(2L);
    }
}
