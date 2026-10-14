package kr.dku.councilflow.analysis;

import java.util.List;

/**
 * 모델 응답을 형식 검증한 결과(아직 의미 검증 전).
 * CLAUDE.md 의 "AI 출력 스키마"와 같은 구조다. 다만 HCX-007 Structured Outputs 문서에 null 타입이 없어서
 * 모델에게는 모르는 값을 빈 문자열("")로 받고, 여기서는 빈 문자열을 그대로 둔다(저장할 때 null 로 바꾼다).
 */
public record AiAnalysis(List<Agenda> agendas) {

    public record Agenda(String agenda, List<Decision> decisions) {
    }

    public record Decision(String summary, String decisionStatus, List<String> sourceSegmentIds,
                           List<Task> tasks, Expense expenseProposal) {
    }

    /**
     * ownerDepartment: 회의에서 말한 담당 그대로(말하지 않았으면 "").
     * department·departmentReason: AI가 등록된 부서 중에서 고른 담당과 이유(고를 근거가 없으면 "").
     */
    public record Task(String title, String ownerDepartment, String department, String departmentReason,
                       String dueDate, List<String> sourceSegmentIds) {
    }

    public record Expense(String item, String amount, String fundSource) {
    }
}
