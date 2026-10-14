package kr.dku.councilflow.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 긴 회의를 여러 구간으로 나눠 분석한 결과를 하나로 합친다.
 * 요약이 같은(공백·문장부호 무시) 결정은 하나로 보고 근거 발언과 업무를 합친다.
 * 같은 결정이 여러 구간에 나오면 뒤 구간의 decision_status 를 따른다(회의 후반에 확정되는 경우가 많아서).
 */
public final class AnalysisMerger {

    private AnalysisMerger() {
    }

    public static AiAnalysis merge(List<AiAnalysis> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        Map<String, String> agendaNames = new LinkedHashMap<>();
        Map<String, List<String>> decisionKeysByAgenda = new LinkedHashMap<>();
        Map<String, MutableDecision> decisions = new LinkedHashMap<>();

        for (AiAnalysis part : parts) {
            for (AiAnalysis.Agenda agenda : part.agendas()) {
                String agendaKey = normalize(agenda.agenda());
                agendaNames.putIfAbsent(agendaKey, agenda.agenda());
                for (AiAnalysis.Decision decision : agenda.decisions()) {
                    String key = normalize(decision.summary());
                    MutableDecision existing = decisions.get(key);
                    if (existing == null) {
                        decisions.put(key, new MutableDecision(decision));
                        decisionKeysByAgenda.computeIfAbsent(agendaKey, k -> new ArrayList<>()).add(key);
                    } else {
                        existing.absorb(decision);
                    }
                }
            }
        }

        List<AiAnalysis.Agenda> agendas = new ArrayList<>();
        decisionKeysByAgenda.forEach((agendaKey, keys) -> agendas.add(new AiAnalysis.Agenda(
                agendaNames.get(agendaKey),
                keys.stream().map(k -> decisions.get(k).toDecision()).toList())));
        return new AiAnalysis(agendas);
    }

    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}·…“”‘’「」]", "").toLowerCase();
    }

    private static final class MutableDecision {
        private final String summary;
        private String status;
        private final LinkedHashSet<String> ids;
        private final Map<String, AiAnalysis.Task> tasks = new LinkedHashMap<>();
        private AiAnalysis.Expense expense;

        MutableDecision(AiAnalysis.Decision decision) {
            this.summary = decision.summary();
            this.status = decision.decisionStatus();
            this.ids = new LinkedHashSet<>(decision.sourceSegmentIds());
            this.expense = decision.expenseProposal();
            decision.tasks().forEach(this::addTask);
        }

        void absorb(AiAnalysis.Decision decision) {
            status = decision.decisionStatus();
            ids.addAll(decision.sourceSegmentIds());
            if (expense == null) {
                expense = decision.expenseProposal();
            }
            decision.tasks().forEach(this::addTask);
        }

        private void addTask(AiAnalysis.Task task) {
            tasks.merge(normalize(task.title()), task, (a, b) -> {
                LinkedHashSet<String> merged = new LinkedHashSet<>(a.sourceSegmentIds());
                merged.addAll(b.sourceSegmentIds());
                boolean useB = a.department().isEmpty() && !b.department().isEmpty();
                return new AiAnalysis.Task(a.title(),
                        a.ownerDepartment().isEmpty() ? b.ownerDepartment() : a.ownerDepartment(),
                        useB ? b.department() : a.department(),
                        useB ? b.departmentReason() : a.departmentReason(),
                        a.dueDate().isEmpty() ? b.dueDate() : a.dueDate(),
                        List.copyOf(merged));
            });
        }

        AiAnalysis.Decision toDecision() {
            return new AiAnalysis.Decision(summary, status, List.copyOf(ids), List.copyOf(tasks.values()), expense);
        }
    }
}
