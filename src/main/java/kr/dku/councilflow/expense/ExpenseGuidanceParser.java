package kr.dku.councilflow.expense;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 1단계 검증(형식): 모델이 준 문자열이 지출 안내 스키마 모양의 JSON 인지만 확인한다.
 * 조항 이름이 후보 안에 있는지, 인용 구절이 원문에 있는지는 2단계(ExpenseGuidanceValidator)에서 따로 본다.
 */
public class ExpenseGuidanceParser {

    private final ObjectMapper json;

    public ExpenseGuidanceParser(ObjectMapper json) {
        this.json = json;
    }

    /** 형식이 틀렸을 때. reason 은 재요청 안내문에 그대로 쓴다(어느 필드가 틀렸는지만, 원문은 넣지 않음). */
    public static class InvalidResponse extends Exception {
        InvalidResponse(String reason) {
            super(reason);
        }
    }

    public ParsedGuidance parse(String content) throws InvalidResponse {
        JsonNode root;
        try {
            root = json.readTree(stripCodeFence(content));
        } catch (JacksonException e) {
            throw new InvalidResponse("JSON으로 읽을 수 없음");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidResponse("최상위가 JSON 객체가 아님");
        }
        List<ParsedGuidance.Cited> approvals = cited(array(root, "required_approvals"), "required_approvals");
        List<ParsedGuidance.Cited> evidence = cited(array(root, "required_evidence"), "required_evidence");

        // restrictions 는 구조화 출력이 보내 주지만, 빠져 있으면 없는 것으로 본다(형식 오류로 다시 요청하지 않는다).
        List<ParsedGuidance.Restriction> restrictions = new ArrayList<>();
        JsonNode restrictionNodes = root.get("restrictions");
        if (restrictionNodes != null && !restrictionNodes.isNull()) {
            if (!restrictionNodes.isArray()) {
                throw new InvalidResponse("restrictions 가 배열이 아님");
            }
            for (int i = 0; i < restrictionNodes.size(); i++) {
                String path = "restrictions[" + i + "]";
                JsonNode node = object(restrictionNodes.get(i), path);
                String kind = string(node, "kind", path + ".kind");
                if (!kind.equals(GuidanceResult.PROHIBITED) && !kind.equals(GuidanceResult.LIMITED)) {
                    throw new InvalidResponse(path + ".kind 값이 PROHIBITED/LIMITED 중 하나가 아님");
                }
                restrictions.add(new ParsedGuidance.Restriction(kind,
                        string(node, "text", path + ".text"),
                        strings(node, "clause_ids", path + ".clause_ids"),
                        string(node, "quote", path + ".quote")));
            }
        }

        JsonNode conflictNodes = array(root, "conflicts");
        List<ParsedGuidance.Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < conflictNodes.size(); i++) {
            String path = "conflicts[" + i + "]";
            JsonNode node = object(conflictNodes.get(i), path);
            conflicts.add(new ParsedGuidance.Conflict(
                    string(node, "description", path + ".description"),
                    strings(node, "clause_ids", path + ".clause_ids")));
        }

        List<String> missing = strings(root, "missing_facts", "missing_facts");
        String guidance = string(root, "guidance", "guidance");
        return new ParsedGuidance(approvals, evidence, restrictions, conflicts, missing, guidance);
    }

    private List<ParsedGuidance.Cited> cited(JsonNode nodes, String field) throws InvalidResponse {
        List<ParsedGuidance.Cited> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            String path = field + "[" + i + "]";
            JsonNode node = object(nodes.get(i), path);
            result.add(new ParsedGuidance.Cited(
                    string(node, "text", path + ".text"),
                    strings(node, "clause_ids", path + ".clause_ids"),
                    string(node, "quote", path + ".quote")));
        }
        return result;
    }

    private static JsonNode object(JsonNode node, String path) throws InvalidResponse {
        if (node == null || !node.isObject()) {
            throw new InvalidResponse(path + " 가 객체가 아님");
        }
        return node;
    }

    private static JsonNode array(JsonNode parent, String field) throws InvalidResponse {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new InvalidResponse(field + " 가 배열이 아니거나 없음");
        }
        return node;
    }

    /** 문자열 배열. 문자열이 아닌 값이 하나라도 있으면 형식 오류. */
    private static List<String> strings(JsonNode parent, String field, String path) throws InvalidResponse {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new InvalidResponse(path + " 가 배열이 아니거나 없음");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isString()) {
                throw new InvalidResponse(path + " 안에 문자열이 아닌 값이 있음");
            }
            values.add(item.asString().trim());
        }
        return values;
    }

    /** 문자열 필드. null 은 빈 문자열(모르는 값)로 본다. 비어 있는지는 2단계에서 따진다. */
    private static String string(JsonNode parent, String field, String path) throws InvalidResponse {
        JsonNode node = parent.get(field);
        if (node == null) {
            throw new InvalidResponse(path + " 가 없음");
        }
        if (node.isNull()) {
            return "";
        }
        if (!node.isString() && !node.isNumber()) {
            throw new InvalidResponse(path + " 가 문자열이 아님");
        }
        return node.asString().trim();
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
