package kr.dku.councilflow.analysis;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.dku.councilflow.decision.AiDecisionStatus;
import kr.dku.councilflow.decision.Decision;
import kr.dku.councilflow.decision.DecisionRepository;
import kr.dku.councilflow.decision.DecisionStatus;
import kr.dku.councilflow.decision.EvidenceStatus;
import kr.dku.councilflow.decision.ExpenseProposal;
import kr.dku.councilflow.decision.Task;
import kr.dku.councilflow.decision.TaskRepository;
import kr.dku.councilflow.job.JobFailure;
import kr.dku.councilflow.meeting.Meeting;
import kr.dku.councilflow.meeting.Segment;
import kr.dku.councilflow.meeting.SegmentRepository;
import kr.dku.councilflow.org.Department;
import kr.dku.councilflow.org.DepartmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2단계 검증(의미)과 저장. 형식 검증을 통과한 AI 결과를 결정·업무 후보(PROPOSED)로 저장한다.
 *
 * - 근거: 인용한 발언 ID가 이 회의록(현재 전사 버전)에 실제로 있어야 한다. 없거나 하나라도 틀리면
 *   후보를 버리지 않고 "근거 확인 필요"(NEEDS_CHECK)로 표시한다(원칙 3).
 * - 담당: 등록된 부서 이름과 정확히 같을 때만 담당으로 넣는다. 아니면 비우고, 역할 키워드로 고른 부서는
 *   추천(suggested)에만 넣는다(원칙 2).
 * - 기한: YYYY-MM-DD 로 읽히는 날짜만. 아니면 비운다.
 */
@Component
public class ProposalWriter {

    private final DecisionRepository decisions;
    private final TaskRepository tasks;
    private final SegmentRepository segments;
    private final DepartmentRepository departments;

    ProposalWriter(DecisionRepository decisions, TaskRepository tasks, SegmentRepository segments,
                   DepartmentRepository departments) {
        this.decisions = decisions;
        this.tasks = tasks;
        this.segments = segments;
        this.departments = departments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Meeting meeting, int transcriptVersion, AiAnalysis analysis) {
        if (meeting.getTranscriptVersion() != transcriptVersion) {
            throw JobFailure.permanent("TRANSCRIPT_CHANGED");
        }
        if (decisions.existsByMeetingIdAndStatusNot(meeting.getId(), DecisionStatus.PROPOSED)) {
            throw JobFailure.permanent("ALREADY_REVIEWED");
        }
        replaceOldProposals(meeting.getId());

        Map<String, Long> segmentIdByLabel = segments
                .findByMeetingIdAndTranscriptVersionOrderBySeq(meeting.getId(), transcriptVersion).stream()
                .collect(Collectors.toMap(Segment::label, Segment::getId));
        List<Department> orgDepartments = departments.findByOrganizationIdAndActiveTrueOrderById(meeting.getOrgId());

        for (AiAnalysis.Agenda agenda : analysis.agendas()) {
            String agendaName = agenda.agenda().isBlank() ? "(안건 이름 없음)" : agenda.agenda();
            for (AiAnalysis.Decision candidate : agenda.decisions()) {
                List<String> notes = new ArrayList<>();
                Evidence evidence = resolve(candidate.sourceSegmentIds(), segmentIdByLabel, notes);
                Decision decision = decisions.save(Decision.proposal(meeting, truncate(agendaName, 300),
                        candidate.summary(), AiDecisionStatus.valueOf(candidate.decisionStatus()), evidence.ids(),
                        evidence.status(), notes, expense(candidate.expenseProposal(), notes)));
                for (AiAnalysis.Task taskCandidate : candidate.tasks()) {
                    tasks.save(task(decision, taskCandidate, segmentIdByLabel, orgDepartments));
                }
            }
        }
    }

    private void replaceOldProposals(long meetingId) {
        List<Decision> old = decisions.findByMeetingIdOrderById(meetingId);
        if (old.isEmpty()) {
            return;
        }
        tasks.deleteAll(tasks.findByDecisionIdInOrderById(old.stream().map(Decision::getId).toList()));
        decisions.deleteAll(old);
        decisions.flush();
    }

    private Task task(Decision decision, AiAnalysis.Task candidate, Map<String, Long> segmentIdByLabel,
                      List<Department> orgDepartments) {
        List<String> notes = new ArrayList<>();
        Evidence evidence = resolve(candidate.sourceSegmentIds(), segmentIdByLabel, notes);

        String said = candidate.ownerDepartment();
        Department department = DepartmentMatcher.exact(said, orgDepartments).orElse(null);
        Department suggested = null;
        if (department == null) {
            if (!said.isBlank()) {
                notes.add("회의에서 말한 담당 '" + said + "'은(는) 등록된 부서 이름과 달라 비워 두었습니다.");
            }
            suggested = DepartmentMatcher.suggest(said + " " + candidate.title(), orgDepartments).orElse(null);
        }

        LocalDate dueDate = null;
        if (!candidate.dueDate().isBlank()) {
            try {
                dueDate = LocalDate.parse(candidate.dueDate());
            } catch (DateTimeParseException e) {
                notes.add("기한 '" + candidate.dueDate() + "'을(를) 날짜로 읽지 못해 비워 두었습니다.");
            }
        }

        return Task.proposal(decision, truncate(candidate.title(), 300), department, dueDate, suggested,
                said.isBlank() ? null : truncate(said, 100), evidence.ids(), evidence.status(), notes);
    }

    private record Evidence(List<Long> ids, EvidenceStatus status) {
    }

    private static Evidence resolve(List<String> labels, Map<String, Long> segmentIdByLabel, List<String> notes) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String label : new LinkedHashSet<>(labels)) {
            Long id = segmentIdByLabel.get(normalizeLabel(label));
            if (id == null) {
                unknown.add(label);
            } else {
                ids.add(id);
            }
        }
        if (labels.isEmpty()) {
            notes.add("근거 발언이 없습니다.");
        }
        if (!unknown.isEmpty()) {
            notes.add("회의록에 없는 발언 ID를 인용했습니다: " + String.join(", ", unknown));
        }
        EvidenceStatus status = labels.isEmpty() || !unknown.isEmpty() ? EvidenceStatus.NEEDS_CHECK : EvidenceStatus.OK;
        return new Evidence(List.copyOf(ids), status);
    }

    /** "s12", "[S12]" 처럼 조금 다르게 써도 같은 ID로 본다. */
    private static String normalizeLabel(String label) {
        return label.replaceAll("[\\[\\]\\s]", "").toUpperCase();
    }

    private static ExpenseProposal expense(AiAnalysis.Expense expense, List<String> notes) {
        if (expense == null) {
            return null;
        }
        Long amount = null;
        String rawAmount = expense.amount().replaceAll("[,\\s원]", "");
        if (!rawAmount.isEmpty()) {
            if (rawAmount.matches("\\d{1,15}")) {
                amount = Long.parseLong(rawAmount);
            } else {
                notes.add("지출 금액 '" + expense.amount() + "'을(를) 숫자로 읽지 못해 비워 두었습니다.");
            }
        }
        String fundSource = expense.fundSource().isBlank() ? null : expense.fundSource();
        return new ExpenseProposal(expense.item(), amount, fundSource);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
