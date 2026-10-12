package kr.dku.councilflow.expense;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 재확인(ExpenseApplicabilityChecker)의 답을 검증된 안내에 반영한다. 순수 계산이라 스프링 없이 테스트한다.
 *
 * 항목에는 이름을 붙인다: 필요 승인 A1…, 필요 증빙 E1…, 금지·제한 R1… (각각 나온 순서).
 * - 승인·증빙: applies 가 NO 거나 quote_supports 가 NO 면 체크리스트에서 빼고 "따로 둔 항목"(setAside)으로 보낸다.
 * - 금지·제한: applies 가 YES 면 관련 있음, NO 면 관련 없음으로 정한다. UNSURE·답 없음은 글자 비교 결과를 그대로 둔다.
 *   (글자 비교는 기프티콘 ↔ 바코드 캡쳐 같은 다른 말을 같은 일로 보지 못해 관련 있는 금지를 놓친다.)
 * - 지우는 것은 없다. 따로 둔 항목은 화면에서 접어 보여 주고, 사람이 해당하는지 확인한다.
 * - 항목이 따로 둬졌거나 금지의 관련성이 바뀌면 모델이 쓴 요약 안내문이 그 내용을 담고 있을 수 있어 버린다.
 */
public final class ExpenseApplicability {

    public enum Answer { YES, NO, UNSURE }

    public record Verdict(Answer applies, Answer quoteSupports) {
    }

    /** 확인할 항목 이름과 내용(모델에게 보낼 때 쓴다). */
    public record Target(String name, String kind, String text, String quote, List<Long> clauseIds) {
    }

    private ExpenseApplicability() {
    }

    static String approvalName(int index) {
        return "A" + (index + 1);
    }

    static String evidenceName(int index) {
        return "E" + (index + 1);
    }

    static String restrictionName(int index) {
        return "R" + (index + 1);
    }

    public static List<Target> targets(ExpenseGuidanceValidator.Validated v) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < v.approvals().size(); i++) {
            ExpenseGuidanceValidator.Item item = v.approvals().get(i);
            targets.add(new Target(approvalName(i), "필요한 승인", item.text(), item.quote(), item.clauseIds()));
        }
        for (int i = 0; i < v.evidence().size(); i++) {
            ExpenseGuidanceValidator.Item item = v.evidence().get(i);
            targets.add(new Target(evidenceName(i), "필요한 증빙", item.text(), item.quote(), item.clauseIds()));
        }
        for (int i = 0; i < v.restrictions().size(); i++) {
            GuidanceResult.Restriction r = v.restrictions().get(i);
            String kind = GuidanceResult.PROHIBITED.equals(r.kind()) ? "금지" : "한도·조건";
            targets.add(new Target(restrictionName(i), kind, r.text(), r.quote(), r.clauseIds()));
        }
        return targets;
    }

    public static ExpenseGuidanceValidator.Validated apply(ExpenseGuidanceValidator.Validated v,
                                                           Map<String, Verdict> verdicts) {
        List<GuidanceResult.SetAside> setAside = new ArrayList<>(v.setAside());
        List<ExpenseGuidanceValidator.Item> approvals = split(v.approvals(), true, verdicts, setAside);
        List<ExpenseGuidanceValidator.Item> evidence = split(v.evidence(), false, verdicts, setAside);

        int newlyAside = setAside.size() - v.setAside().size();
        int changedRelated = 0;
        List<GuidanceResult.Restriction> restrictions = new ArrayList<>();
        for (int i = 0; i < v.restrictions().size(); i++) {
            GuidanceResult.Restriction r = v.restrictions().get(i);
            Verdict verdict = verdicts.get(restrictionName(i));
            Boolean related = r.related();
            if (verdict != null && verdict.applies() == Answer.YES) {
                related = Boolean.TRUE;
            } else if (verdict != null && verdict.applies() == Answer.NO) {
                related = Boolean.FALSE;
            }
            if (r.relatedOrTrue() != !Boolean.FALSE.equals(related)) {
                changedRelated++;
            }
            restrictions.add(new GuidanceResult.Restriction(r.kind(), r.text(), r.clauseIds(), r.quote(), related));
        }

        List<String> notes = new ArrayList<>(v.droppedNotes());
        if (newlyAside > 0) {
            notes.add("AI 재확인에서 이 지출에 해당하지 않거나 인용 구절과 문구가 맞지 않는다고 본 승인·증빙 " + newlyAside
                    + "개를 체크리스트에서 빼고 따로 표시했습니다. 지우지 않았으니 해당하는지 확인해 주세요.");
        }
        if (changedRelated > 0) {
            notes.add("AI 재확인으로 금지·제한 " + changedRelated + "개의 관련 여부를 글자 비교 결과에서 바꿨습니다.");
        }
        String guidance = v.guidance();
        if (guidance != null && (newlyAside > 0 || changedRelated > 0)) {
            guidance = null;
            notes.add("재확인으로 항목이 바뀌어 AI가 쓴 요약 안내문은 표시하지 않습니다.");
        }
        ExpenseReviewStatus status = ExpenseGuidanceValidator.statusOf(approvals, evidence, restrictions, v.conflicts());
        if (status == ExpenseReviewStatus.NEEDS_CHECK) {
            guidance = null;
        }
        return new ExpenseGuidanceValidator.Validated(status, approvals, evidence, restrictions, v.conflicts(),
                v.missingFacts(), guidance, v.droppedCount(), notes, setAside);
    }

    private static List<ExpenseGuidanceValidator.Item> split(List<ExpenseGuidanceValidator.Item> items,
                                                             boolean approval, Map<String, Verdict> verdicts,
                                                             List<GuidanceResult.SetAside> setAside) {
        List<ExpenseGuidanceValidator.Item> kept = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ExpenseGuidanceValidator.Item item = items.get(i);
            Verdict verdict = verdicts.get(approval ? approvalName(i) : evidenceName(i));
            String reason = null;
            if (verdict != null && verdict.applies() == Answer.NO) {
                reason = GuidanceResult.NOT_APPLICABLE;
            } else if (verdict != null && verdict.quoteSupports() == Answer.NO) {
                reason = GuidanceResult.QUOTE_MISMATCH;
            }
            if (reason == null) {
                kept.add(item);
            } else {
                setAside.add(new GuidanceResult.SetAside(approval ? "APPROVAL" : "EVIDENCE", item.text(),
                        item.clauseIds(), item.quote(), reason));
            }
        }
        return kept;
    }
}
