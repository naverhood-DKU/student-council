package kr.dku.councilflow.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 1단계 검증(형식): 모델이 준 문자열이 스키마 모양의 JSON인지 확인한다.
 * 발언 ID가 실제로 있는지, 날짜·부서가 맞는지는 2단계(ProposalWriter)에서 따로 본다.
 */
public class AiResponseParser {

    private static final Set<String> DECISION_STATUSES = Set.of("CONFIRMED", "ON_HOLD", "DISCUSSING");

    private final ObjectMapper json;

    public AiResponseParser(ObjectMapper json) {
        this.json = json;
    }

    /** 형식이 틀렸을 때. reason 은 재요청 안내문에 그대로 쓴다(어느 필드가 틀렸는지만, 원문은 넣지 않음). */
    public static class InvalidResponse extends Exception {
        InvalidResponse(String reason) {
            super(reason);
        }
    }

    public AiAnalysis parse(String content) throws InvalidResponse {
        JsonNode root;
        try {
            root = json.readTree(stripCodeFence(content));
        } catch (JacksonException e) {
            throw new InvalidResponse("JSON으로 읽을 수 없음");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidResponse("최상위가 JSON 객체가 아님");
        }
        JsonNode agendas = array(root, "agendas", "agendas");
        List<AiAnalysis.Agenda> result = new ArrayList<>();
        for (int a = 0; a < agendas.size(); a++) {
            String path = "agendas[" + a + "]";
            JsonNode agenda = object(agendas.get(a), path);
            JsonNode decisions = array(agenda, "decisions", path + ".decisions");
            List<AiAnalysis.Decision> decisionList = new ArrayList<>();
            for (int d = 0; d < decisions.size(); d++) {
                decisionList.add(decision(decisions.get(d), path + ".decisions[" + d + "]"));
            }
            result.add(new AiAnalysis.Agenda(text(agenda, "agenda", path + ".agenda", false), decisionList));
        }
        return new AiAnalysis(result);
    }

    private AiAnalysis.Decision decision(JsonNode node, String path) throws InvalidResponse {
        object(node, path);
        String summary = text(node, "summary", path + ".summary", true);
        String status = text(node, "decision_status", path + ".decision_status", true);
        if (!DECISION_STATUSES.contains(status)) {
            throw new InvalidResponse(path + ".decision_status 값이 CONFIRMED/ON_HOLD/DISCUSSING 중 하나가 아님");
        }
        List<String> ids = ids(node, path + ".source_segment_ids");
        JsonNode tasks = array(node, "tasks", path + ".tasks");
        List<AiAnalysis.Task> taskList = new ArrayList<>();
        for (int t = 0; t < tasks.size(); t++) {
            String taskPath = path + ".tasks[" + t + "]";
            JsonNode task = object(tasks.get(t), taskPath);
            taskList.add(new AiAnalysis.Task(
                    text(task, "title", taskPath + ".title", true),
                    text(task, "owner_department", taskPath + ".owner_department", false),
                    text(task, "department", taskPath + ".department", false),
                    text(task, "department_reason", taskPath + ".department_reason", false),
                    text(task, "due_date", taskPath + ".due_date", false),
                    ids(task, taskPath + ".source_segment_ids")));
        }
        AiAnalysis.Expense expense = null;
        JsonNode expenseNode = node.get("expense_proposal");
        if (expenseNode != null && !expenseNode.isNull()) {
            String expensePath = path + ".expense_proposal";
            object(expenseNode, expensePath);
            String item = text(expenseNode, "item", expensePath + ".item", false);
            if (!item.isBlank()) {
                expense = new AiAnalysis.Expense(item,
                        text(expenseNode, "amount", expensePath + ".amount", false),
                        text(expenseNode, "fund_source", expensePath + ".fund_source", false));
            }
        }
        return new AiAnalysis.Decision(summary, status, ids, taskList, expense);
    }

    private static List<String> ids(JsonNode parent, String path) throws InvalidResponse {
        JsonNode array = array(parent, "source_segment_ids", path);
        List<String> ids = new ArrayList<>();
        for (JsonNode id : array) {
            if (!id.isString()) {
                throw new InvalidResponse(path + " 안에 문자열이 아닌 값이 있음");
            }
            ids.add(id.asString().trim());
        }
        return ids;
    }

    private static JsonNode object(JsonNode node, String path) throws InvalidResponse {
        if (node == null || !node.isObject()) {
            throw new InvalidResponse(path + " 가 객체가 아님");
        }
        return node;
    }

    private static JsonNode array(JsonNode parent, String field, String path) throws InvalidResponse {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new InvalidResponse(path + " 가 배열이 아니거나 없음");
        }
        return node;
    }

    /** 문자열 필드. null 은 빈 문자열로 본다(모르는 값). required 면 비어 있으면 안 된다. */
    private static String text(JsonNode parent, String field, String path, boolean required) throws InvalidResponse {
        JsonNode node = parent.get(field);
        if (node == null && required) {
            throw new InvalidResponse(path + " 가 없음");
        }
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isString() && !node.isNumber()) {
            throw new InvalidResponse(path + " 가 문자열이 아님");
        }
        String value = node.asString().trim();
        if (required && value.isEmpty()) {
            throw new InvalidResponse(path + " 가 비어 있음");
        }
        return value;
    }

    private static String stripCodeFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence);
            }
        }
        return trimmed;
    }
}
