package kr.dku.councilflow.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import kr.dku.councilflow.IntegrationTest;
import kr.dku.councilflow.job.JobRunner;
import kr.dku.councilflow.support.FakeClovaServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 단계 2 완료 기준 시나리오: 단과대(AI 융합대학)가 학과 A(컴공)·B(소프트웨어)에 전달
 * → A 수락·제출·완료 확정, B 확인 요청 → 집계 "완료 1 / 확인 요청 1 / 전체 2".
 * 잘못된 전이는 409, 다른 조직 접근은 403.
 */
class DeliveryScenarioTest extends IntegrationTest {

    static final String TRANSCRIPT = """
            회장: 단과대 체육대회는 11월 7일에 하기로 확정합니다.
            회장: 학과별로 참가 명단을 10월 31일까지 받겠습니다.
            기획부장: 경기 종목은 다음 회의에서 정하죠.
            """;
    static final String AI_RESULT = """
            {"agendas":[{"agenda":"단과대 체육대회","decisions":[
              {"summary":"단과대 체육대회를 11월 7일에 열고, 학과별 참가 명단을 10월 31일까지 받는다.",
               "decision_status":"CONFIRMED","source_segment_ids":["S1","S2"],"tasks":[]},
              {"summary":"경기 종목은 다음 회의에서 정한다.","decision_status":"ON_HOLD","source_segment_ids":["S3"],"tasks":[]}
            ]}]}
            """;

    @Autowired
    JobRunner jobRunner;

    long college;
    long cse;
    long sw;
    long union;
    long collegeMeetingId;
    long approvedDecision;
    long pendingDecision;
    String collegeChair;

    @BeforeEach
    void collegeApprovesDecision() throws Exception {
        college = orgId("AI 융합대학 학생회");
        cse = orgId("컴퓨터공학과 학생회");
        sw = orgId("소프트웨어학과 학생회");
        union = orgId("총학생회");
        collegeChair = bearer("ai");

        collegeMeetingId = createMeeting("ai", college, TRANSCRIPT);
        CLOVA.enqueue(FakeClovaServer.ok(AI_RESULT));
        requestAnalysis("ai", collegeMeetingId);
        jobRunner.runOnce();
        String proposals = mvc.perform(get("/api/meetings/{id}/proposals", collegeMeetingId)
                        .header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andReturn().getResponse().getContentAsString();
        approvedDecision = num(proposals, "$.decisions[0].id");
        pendingDecision = num(proposals, "$.decisions[1].id");
        mvc.perform(post("/api/decisions/{id}/approve", approvedDecision)
                        .header(HttpHeaders.AUTHORIZATION, collegeChair)
                        .header(HttpHeaders.IF_MATCH, num(proposals, "$.decisions[0].version")))
                .andExpect(status().isOk());
    }

    private static long num(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private ResultActions send(String token, long decisionId, Object body) throws Exception {
        return mvc.perform(post("/api/decisions/{id}/deliveries", decisionId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private ResultActions patchDelivery(String token, long deliveryId, long version, Object body) throws Exception {
        return mvc.perform(patch("/api/deliveries/{id}", deliveryId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** 수신함에서 이 결정으로 받은 전달 하나 */
    private String inboxItem(String token, long orgId) throws Exception {
        String inbox = mvc.perform(get("/api/orgs/{id}/inbox", orgId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> items = JsonPath.read(inbox, "$[?(@.decisionId == " + approvedDecision + ")]");
        assertThat(items).hasSize(1);
        return JSON.writeValueAsString(items.get(0));
    }

    private String sendToBothDepartments() throws Exception {
        return send(collegeChair, approvedDecision, Map.of(
                "targetOrgIds", List.of(cse, sw),
                "requestType", "COOPERATE",
                "dueDate", "2026-10-31",
                "message", "학과별 참가 명단을 정리해서 링크로 올려 주세요."))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 단과대가_학과_두_곳에_전달하고_집계한다() throws Exception {
        // 요청할 수 있는 그룹: 자기 그룹을 뺀 모든 그룹(상위 총학 포함)
        mvc.perform(get("/api/orgs/{id}/request-targets", college).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(jsonPath("$[*].name", hasItems("총학생회", "컴퓨터공학과 학생회", "소프트웨어학과 학생회")))
                .andExpect(jsonPath("$[*].name", not(hasItem("AI 융합대학 학생회"))));

        String sent = sendToBothDepartments();
        assertThat(num(sent, "$.tally.total")).isEqualTo(2);
        assertThat(num(sent, "$.tally.counts.PENDING")).isEqualTo(2);

        // ── 학과 A(컴공): 수락 → 담당 부서 지정 → 담당 부서원이 시작·제출 → 단과대 완료 확정
        String ceChair = bearer("ce");
        String a = inboxItem(ceChair, cse);
        assertThat((String) JsonPath.read(a, "$.fromOrgName")).isEqualTo("AI 융합대학 학생회");
        assertThat((String) JsonPath.read(a, "$.message")).contains("참가 명단");
        long aId = num(a, "$.id");

        String accepted = patchDelivery(ceChair, aId, num(a, "$.version"), Map.of("status", "ACCEPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        long planning = departmentIdOf(ceChair, cse, "기획부");
        String assigned = patchDelivery(ceChair, aId, num(accepted, "$.version"),
                Map.of("internalDepartmentId", planning))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internalDepartmentName").value("기획부"))
                .andReturn().getResponse().getContentAsString();

        // 담당이 아닌 부서원은 시작할 수 없다.
        patchDelivery(bearer("ce.pr"), aId, num(assigned, "$.version"), Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isForbidden());
        String planner = bearer("ce.plan");
        patchDelivery(planner, aId, num(assigned, "$.version"), Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 링크·설명 둘 다 없으면 400, 링크 형식이 틀리면 400
        mvc.perform(post("/api/deliveries/{id}/submissions", aId)
                        .header(HttpHeaders.AUTHORIZATION, planner)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/deliveries/{id}/submissions", aId)
                        .header(HttpHeaders.AUTHORIZATION, planner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resultLink\": \"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
        String submitted = mvc.perform(post("/api/deliveries/{id}/submissions", aId)
                        .header(HttpHeaders.AUTHORIZATION, planner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "resultLink", "https://docs.example.com/cse-roster",
                                "description", "참가자 24명 명단입니다."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        long submissionId = num(submitted, "$.submissions[0].id");

        // 받은 조직은 확정할 수 없고, 보낸 조직의 회의 책임자만 확정한다.
        review(ceChair, submissionId, Map.of("approve", true)).andExpect(status().isForbidden());
        review(bearer("ai.plan"), submissionId, Map.of("approve", true)).andExpect(status().isForbidden());
        review(collegeChair, submissionId, Map.of("approve", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.submissions[0].reviewStatus").value("APPROVED"));
        // 이미 확정한 제출물은 다시 검토할 수 없다.
        review(collegeChair, submissionId, Map.of("approve", false, "comment", "다시")).andExpect(status().isConflict());

        // ── 학과 B(소프트웨어): 잘못된 전이 409, 사유 없는 확인 요청 400, 사유 있으면 확인 요청
        String swChair = bearer("sw");
        String b = inboxItem(swChair, sw);
        long bId = num(b, "$.id");
        patchDelivery(swChair, bId, num(b, "$.version"), Map.of("status", "IN_PROGRESS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        patchDelivery(swChair, bId, num(b, "$.version"), Map.of("status", "CLARIFICATION_REQUESTED"))
                .andExpect(status().isBadRequest());
        patchDelivery(swChair, bId, num(b, "$.version"),
                Map.of("status", "CLARIFICATION_REQUESTED", "reason", "명단 양식이 따로 있나요?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLARIFICATION_REQUESTED"));

        // ── 집계: 완료 1 / 확인 요청 1 / 전체 2
        mvc.perform(get("/api/decisions/{id}/delivery-summary", approvedDecision)
                        .header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tally.total").value(2))
                .andExpect(jsonPath("$.tally.counts.DONE").value(1))
                .andExpect(jsonPath("$.tally.counts.CLARIFICATION_REQUESTED").value(1))
                .andExpect(jsonPath("$.targets[*].toOrgName", containsInAnyOrder("컴퓨터공학과 학생회", "소프트웨어학과 학생회")))
                .andExpect(jsonPath("$.targets[?(@.toOrgName == '소프트웨어학과 학생회')].clarificationReason",
                        contains("명단 양식이 따로 있나요?")));

        // 보낸 요청 목록에도 같은 집계
        mvc.perform(get("/api/orgs/{id}/sent-deliveries", college).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(jsonPath("$[?(@.decisionId == " + approvedDecision + ")].tally.total", contains(2)));
    }

    @Test
    void 확인_요청에_답하면_다시_대기가_되고_기한_변경은_수신함에_보인다() throws Exception {
        sendToBothDepartments();
        String swChair = bearer("sw");
        String b = inboxItem(swChair, sw);
        long bId = num(b, "$.id");
        String asked = patchDelivery(swChair, bId, num(b, "$.version"),
                Map.of("status", "CLARIFICATION_REQUESTED", "reason", "양식이 있나요?"))
                .andReturn().getResponse().getContentAsString();

        // 받은 조직은 답변(PENDING)을 할 수 없다. 보낸 조직의 답변은 내용이 있어야 한다.
        patchDelivery(swChair, bId, num(asked, "$.version"), Map.of("status", "PENDING", "clarificationReply", "x"))
                .andExpect(status().isForbidden());
        patchDelivery(collegeChair, bId, num(asked, "$.version"), Map.of("status", "PENDING"))
                .andExpect(status().isBadRequest());
        String answered = patchDelivery(collegeChair, bId, num(asked, "$.version"),
                Map.of("status", "PENDING", "clarificationReply", "자유 양식입니다."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        // 기한 변경: version 이 오르고 이전 기한이 남는다. 받은 조직은 기한을 바꿀 수 없다.
        patchDelivery(swChair, bId, num(answered, "$.version"), Map.of("dueDate", "2026-11-03"))
                .andExpect(status().isForbidden());
        patchDelivery(collegeChair, bId, num(answered, "$.version"), Map.of("dueDate", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value((int) num(answered, "$.version") + 1));
        String after = inboxItem(swChair, sw);
        assertThat((String) JsonPath.read(after, "$.dueDate")).isEqualTo("2026-11-03");
        assertThat((String) JsonPath.read(after, "$.previousDueDate")).isEqualTo("2026-10-31");
        assertThat((String) JsonPath.read(after, "$.clarificationReply")).isEqualTo("자유 양식입니다.");

        // 오래된 version 으로 바꾸려 하면 409
        patchDelivery(swChair, bId, num(answered, "$.version"), Map.of("status", "ACCEPTED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void 반려하면_진행으로_돌아가고_새로_제출한다() throws Exception {
        sendToBothDepartments();
        String ceChair = bearer("ce");
        String a = inboxItem(ceChair, cse);
        long aId = num(a, "$.id");
        patchDelivery(ceChair, aId, num(a, "$.version"), Map.of("status", "ACCEPTED")).andExpect(status().isOk());
        // 수락 후 바로 제출해도 된다(진행을 거쳐 제출 상태가 됨).
        String first = mvc.perform(post("/api/deliveries/{id}/submissions", aId)
                        .header(HttpHeaders.AUTHORIZATION, ceChair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"명단 초안\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        review(collegeChair, num(first, "$.submissions[0].id"), Map.of("approve", false))
                .andExpect(status().isBadRequest());
        review(collegeChair, num(first, "$.submissions[0].id"), Map.of("approve", false, "comment", "학번이 빠졌어요."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.submissions[0].reviewComment").value("학번이 빠졌어요."));

        mvc.perform(post("/api/deliveries/{id}/submissions", aId)
                        .header(HttpHeaders.AUTHORIZATION, ceChair)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"학번 넣은 명단\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissions", hasSize(2)))
                .andExpect(jsonPath("$.submissions[0].description").value("학번 넣은 명단"))
                .andExpect(jsonPath("$.submissions[1].reviewStatus").value("REJECTED"));
    }

    @Test
    void 해당_없음과_취소는_분모에서_빠지고_끝난_전달은_기한을_못_바꾼다() throws Exception {
        String sent = sendToBothDepartments();
        String swChair = bearer("sw");
        String b = inboxItem(swChair, sw);
        patchDelivery(swChair, num(b, "$.id"), num(b, "$.version"),
                Map.of("status", "NOT_APPLICABLE", "reason", "우리 학과는 참가하지 않습니다."))
                .andExpect(status().isOk());

        List<Number> ids = JsonPath.read(sent, "$.targets[?(@.toOrgName == '컴퓨터공학과 학생회')].id");
        List<Number> versions = JsonPath.read(sent, "$.targets[?(@.toOrgName == '컴퓨터공학과 학생회')].version");
        String cancelled = patchDelivery(collegeChair, ids.get(0).longValue(), versions.get(0).longValue(),
                Map.of("status", "CANCELLED", "reason", "일정 취소"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        patchDelivery(collegeChair, ids.get(0).longValue(), num(cancelled, "$.version"), Map.of("dueDate", "2026-11-10"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/decisions/{id}/delivery-summary", approvedDecision)
                        .header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(jsonPath("$.tally.total").value(0))
                .andExpect(jsonPath("$.tally.counts.DONE").value(0))
                .andExpect(jsonPath("$.tally.counts.CANCELLED").value(1))
                .andExpect(jsonPath("$.tally.counts.NOT_APPLICABLE").value(1));
    }

    @Test
    void 알림은_수락하면_완료된다() throws Exception {
        send(collegeChair, approvedDecision, Map.of("targetOrgIds", List.of(cse), "requestType", "INFORM"))
                .andExpect(status().isCreated());
        String ceChair = bearer("ce");
        String a = inboxItem(ceChair, cse);
        patchDelivery(ceChair, num(a, "$.id"), num(a, "$.version"), Map.of("status", "ACCEPTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void 요청은_승인된_결정만_자기_그룹_말고_다른_그룹에_한_번씩() throws Exception {
        Map<String, Object> body = Map.of("targetOrgIds", List.of(cse), "requestType", "COOPERATE");
        // 승인 안 된 결정
        send(collegeChair, pendingDecision, body).andExpect(status().isConflict());
        // 자기 그룹에는 요청할 수 없다.
        send(collegeChair, approvedDecision, Map.of("targetOrgIds", List.of(college), "requestType", "COOPERATE"))
                .andExpect(status().isBadRequest());
        // 단계와 상관없이 상위 그룹(총학)에도 요청할 수 있다.
        send(collegeChair, approvedDecision, Map.of("targetOrgIds", List.of(union), "requestType", "INFORM"))
                .andExpect(status().isCreated());
        // 보낸 그룹의 owner 가 아니면 403(editor, 다른 그룹 owner)
        send(bearer("ai.plan"), approvedDecision, body).andExpect(status().isForbidden());
        send(bearer("ce"), approvedDecision, body).andExpect(status().isForbidden());
        // 같은 조직에 두 번은 안 된다.
        send(collegeChair, approvedDecision, body).andExpect(status().isCreated());
        send(collegeChair, approvedDecision, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Test
    void 보낸_쪽과_받은_쪽은_서로의_것만_본다() throws Exception {
        String sent = sendToBothDepartments();
        List<Number> ids = JsonPath.read(sent, "$.targets[?(@.toOrgName == '컴퓨터공학과 학생회')].id");
        List<Number> versions = JsonPath.read(sent, "$.targets[?(@.toOrgName == '컴퓨터공학과 학생회')].version");
        long cseDelivery = ids.get(0).longValue();
        String swChair = bearer("sw");
        String ceChair = bearer("ce");

        // 다른 학과(소프트웨어)는 컴공이 받은 전달을 바꾸거나 제출할 수 없다.
        patchDelivery(swChair, cseDelivery, versions.get(0).longValue(), Map.of("status", "ACCEPTED"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/deliveries/{id}/submissions", cseDelivery)
                        .header(HttpHeaders.AUTHORIZATION, swChair)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\": \"x\"}"))
                .andExpect(status().isForbidden());
        // 보낸 조직의 회의 책임자도 받은 조직 대신 수락할 수 없다.
        patchDelivery(collegeChair, cseDelivery, versions.get(0).longValue(), Map.of("status", "ACCEPTED"))
                .andExpect(status().isForbidden());

        // 집계(보낸 쪽 화면)는 받은 조직도, 상위 조직(총학)도 볼 수 없다.
        for (String outsider : List.of(ceChair, bearer("union"))) {
            mvc.perform(get("/api/decisions/{id}/delivery-summary", approvedDecision)
                            .header(HttpHeaders.AUTHORIZATION, outsider))
                    .andExpect(status().isForbidden());
        }
        // 수신함은 그 조직 소속만
        mvc.perform(get("/api/orgs/{id}/inbox", cse).header(HttpHeaders.AUTHORIZATION, swChair))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/inbox", cse).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(status().isForbidden());

        // 원칙 8: 받은 조직은 보낸 조직의 회의·회의록을 볼 수 없다.
        mvc.perform(get("/api/meetings/{id}", collegeMeetingId).header(HttpHeaders.AUTHORIZATION, ceChair))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/meetings/{id}/proposals", collegeMeetingId).header(HttpHeaders.AUTHORIZATION, ceChair))
                .andExpect(status().isForbidden());
        // 원칙 8: 상위 조직(AI 융합대학)은 하위 조직(컴공)의 회의·회의록을 볼 수 없다.
        long cseMeeting = createMeeting("ce", cse, "회장: 명단을 정리합시다.");
        mvc.perform(get("/api/meetings/{id}", cseMeeting).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/meetings/{id}/proposals", cseMeeting).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orgs/{id}/meetings", cse).header(HttpHeaders.AUTHORIZATION, collegeChair))
                .andExpect(status().isForbidden());
    }

    private ResultActions review(String token, long submissionId, Object body) throws Exception {
        return mvc.perform(post("/api/submissions/{id}/review", submissionId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    private long departmentIdOf(String token, long orgId, String name) throws Exception {
        String org = mvc.perform(get("/api/orgs/{id}", orgId).header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(org, "$.departments[?(@.name == '" + name + "')].id");
        return ids.get(0).longValue();
    }
}
