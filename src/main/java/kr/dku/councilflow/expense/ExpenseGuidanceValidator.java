package kr.dku.councilflow.expense;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 2단계 검증(의미): 모델 안내 중 서버가 확인할 수 있는 것만 남긴다. 순수 계산이라 스프링 없이 테스트한다.
 *
 * - 조항 이름은 모델에게 보낸 후보 이름([C1]…)이어야 한다. 하나라도 목록 밖이면 그 항목을 버린다. 비어 있어도 버린다.
 * - 인용 구절(quote)은 근거로 든 조항 원문에 실제로 있어야 한다(NFC 정규화 후 공백 차이만 무시, 최소 4자).
 *   원문은 "실제로 모델에게 보낸 글"이다. 길어서 잘라 보냈다면 잘린 앞부분에서만 찾는다.
 * - 충돌 가능성은 서로 다른 후보 조항 둘 이상을 가리켜야 한다.
 * - "문제없음·승인 불필요·통과" 같은 판정 표현이 든 글은 버린다(원칙 5). 안내문도 마찬가지다.
 * - 승인·증빙으로 분류됐지만 인용 구절 자체가 금지·제한만 말하면 금지·제한으로 옮긴다(RestrictionWords).
 * - 아무것도 남지 않으면 NEEDS_CHECK 다. 근거 없이 남은 안내문(guidance)은 버린다.
 * - 버린 개수와 이유는 결과에 남겨 화면에 알린다. 조용히 없애지 않는다.
 */
public final class ExpenseGuidanceValidator {

    /** 인용 구절의 최소 길이(공백을 뺀 글자 수) */
    public static final int MIN_QUOTE_CHARS = 4;
    static final int MAX_TEXT_CHARS = 500;
    static final int MAX_QUOTE_CHARS = 1000;
    static final int MAX_GUIDANCE_CHARS = 2000;
    private static final int MAX_MISSING_FACTS = 10;

    /** 모델에게 보낸 후보 조항. sentText 는 실제로 보낸 글(잘렸다면 잘린 글)이다. */
    public record Candidate(String name, long clauseId, String sentText) {
    }

    /** 검증을 통과한 필요 승인·증빙 한 줄. clauseIds 는 DB 의 조항 id. */
    public record Item(String text, List<Long> clauseIds, String quote) {
    }

    /** status 는 POSSIBLY_RESTRICTED / POSSIBLE_CONFLICT / PROCEDURE_FOUND / NEEDS_CHECK 중 하나다. */
    public record Validated(ExpenseReviewStatus status, List<Item> approvals, List<Item> evidence,
                            List<GuidanceResult.Restriction> restrictions, List<GuidanceResult.Conflict> conflicts,
                            List<String> missingFacts, String guidance, int droppedCount, List<String> droppedNotes) {
    }

    private enum Reason {
        NO_CLAUSE("근거 조항을 적지 않은 항목"),
        OUT_OF_CANDIDATES("후보 조항 목록에 없는 조항을 근거로 든 항목"),
        BAD_QUOTE("근거 조항 원문에서 확인되지 않는 인용을 쓴 항목"),
        NOT_SUPPORTED("금지·제한 표현이 있는 조항에서 승인·증빙 내용이 아닌 구절을 근거로 든 항목"),
        ONE_CLAUSE_CONFLICT("서로 다른 후보 조항 둘 이상을 가리키지 않은 충돌 항목"),
        VERDICT("판정 표현(괜찮다는 취지의 말)이 든 항목"),
        EMPTY_OR_LONG("내용이 비었거나 너무 긴 항목"),
        UNGROUNDED_GUIDANCE("검증을 통과한 근거가 없는 참고 안내문");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /** 판정처럼 읽히는 표현(공백을 뺀 글자 기준). 규정에 근거 없이 "괜찮다"고 말하게 두지 않는다. */
    private static final List<String> VERDICT_PHRASES = List.of(
            "문제없", "문제가없", "이상없", "이상이없", "승인불필요", "승인이필요하지", "승인필요없", "승인이필요없",
            "통과", "위반없", "위반하지않", "위반이아", "규정에맞", "규정을준수", "지출가능", "집행가능");

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private ExpenseGuidanceValidator() {
    }

    public static Validated validate(ParsedGuidance parsed, List<Candidate> candidates) {
        return validate(parsed, candidates, null);
    }

    /**
     * @param subject 지출 항목·설명·찾을 말을 이은 글. 금지·제한이 이 지출과 직접 관련 있는지 가리는 데 쓴다.
     *                null 이면 모두 관련 있다고 본다.
     */
    public static Validated validate(ParsedGuidance parsed, List<Candidate> candidates, String subject) {
        Map<String, Candidate> byName = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byName.put(normalizeName(candidate.name()), candidate);
        }
        Map<Reason, Integer> dropped = new LinkedHashMap<>();

        List<GuidanceResult.Restriction> restrictions = restrictions(parsed.restrictions(), byName, dropped, subject);
        int[] moved = {0};
        List<Item> approvals = items(parsed.approvals(), byName, dropped, restrictions, moved, subject, true);
        List<Item> evidence = items(parsed.evidence(), byName, dropped, restrictions, moved, subject, false);
        List<GuidanceResult.Conflict> conflicts = conflicts(parsed.conflicts(), byName, dropped);

        List<String> missing = new ArrayList<>();
        for (String fact : parsed.missingFacts()) {
            if (fact.isBlank() || fact.length() > MAX_TEXT_CHARS) {
                continue;
            }
            if (hasVerdict(fact)) {
                dropped.merge(Reason.VERDICT, 1, Integer::sum);
            } else if (!missing.contains(fact) && missing.size() < MAX_MISSING_FACTS) {
                missing.add(fact);
            }
        }

        // 배지는 이 지출과 직접 관련 있어 보이는 금지에만 쓴다. 관련 없어 보이는 금지는 지우지 않고 따로 보여 준다.
        boolean prohibited = restrictions.stream()
                .anyMatch(r -> r.relatedOrTrue() && GuidanceResult.PROHIBITED.equals(r.kind()));
        ExpenseReviewStatus status;
        if (prohibited) {
            status = ExpenseReviewStatus.POSSIBLY_RESTRICTED;
        } else if (!conflicts.isEmpty()) {
            status = ExpenseReviewStatus.POSSIBLE_CONFLICT;
        } else if (!approvals.isEmpty() || !evidence.isEmpty() || !restrictions.isEmpty()) {
            status = ExpenseReviewStatus.PROCEDURE_FOUND;
        } else {
            status = ExpenseReviewStatus.NEEDS_CHECK;
        }

        String guidance = parsed.guidance();
        if (guidance.isBlank()) {
            guidance = null;
        } else if (guidance.length() > MAX_GUIDANCE_CHARS) {
            guidance = null;
            dropped.merge(Reason.EMPTY_OR_LONG, 1, Integer::sum);
        } else if (hasVerdict(guidance)) {
            guidance = null;
            dropped.merge(Reason.VERDICT, 1, Integer::sum);
        } else if (status == ExpenseReviewStatus.NEEDS_CHECK) {
            guidance = null;
            dropped.merge(Reason.UNGROUNDED_GUIDANCE, 1, Integer::sum);
        }

        int total = dropped.values().stream().mapToInt(Integer::intValue).sum();
        List<String> notes = new ArrayList<>();
        dropped.forEach((reason, count) -> notes.add(reason.label + " " + count + "개를 제외했습니다."));
        long unrelated = restrictions.stream().filter(r -> !r.relatedOrTrue()).count();
        if (unrelated > 0) {
            notes.add("지출 항목과 직접 관련이 없어 보이는 금지·제한 " + unrelated + "개는 따로 표시했습니다. 지우지 않았으니 해당하는지 확인해 주세요.");
        }
        if (moved[0] > 0) {
            notes.add("승인·증빙으로 분류됐지만 인용 구절이 금지·제한을 말하는 항목 " + moved[0]
                    + "개를 금지·제한으로 옮겼습니다.");
        }
        return new Validated(status, approvals, evidence, restrictions, conflicts, missing, guidance, total, notes);
    }

    private static List<GuidanceResult.Restriction> restrictions(List<ParsedGuidance.Restriction> parsed,
                                                                 Map<String, Candidate> byName,
                                                                 Map<Reason, Integer> dropped, String subject) {
        List<GuidanceResult.Restriction> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ParsedGuidance.Restriction r : parsed) {
            Reason problem = problem(new ParsedGuidance.Cited(r.text(), r.clauseNames(), r.quote()), byName);
            if (problem != null) {
                dropped.merge(problem, 1, Integer::sum);
                continue;
            }
            if (seen.add(squash(r.text()))) {
                kept.add(new GuidanceResult.Restriction(r.kind(), r.text(), ids(r.clauseNames(), byName), r.quote(),
                        RestrictionWords.relatedTo(r.text(), subject)));
            }
        }
        return kept;
    }

    private static List<Item> items(List<ParsedGuidance.Cited> cited, Map<String, Candidate> byName,
                                    Map<Reason, Integer> dropped, List<GuidanceResult.Restriction> restrictions,
                                    int[] moved, String subject, boolean approval) {
        List<Item> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ParsedGuidance.Cited c : cited) {
            Reason problem = problem(c, byName);
            if (problem != null) {
                dropped.merge(problem, 1, Integer::sum);
                continue;
            }
            if (RestrictionWords.isPlainRestriction(c.quote())) {
                // 인용 구절 자체가 금지·제한이다. AI 의 문구가 아니라 서버가 확인한 인용 구절로 옮긴다.
                boolean exists = restrictions.stream().anyMatch(r -> squash(r.text()).equals(squash(c.quote())));
                if (!exists) {
                    restrictions.add(new GuidanceResult.Restriction(RestrictionWords.kindOf(c.quote()), c.quote(),
                            ids(c.clauseNames(), byName), c.quote(), RestrictionWords.relatedTo(c.quote(), subject)));
                }
                moved[0]++;
                continue;
            }
            // 금지·제한 표현이 있는 조항을 근거로 들었는데 인용 구절에 승인·증빙을 말하는 낱말이 없으면, 금지 조항의
            // 호만 떼어 승인으로 바꿔 쓴 것일 수 있다. 확정된 사실처럼 보이지 않게 버린다.
            boolean citesRestrictive = c.clauseNames().stream()
                    .map(n -> byName.get(normalizeName(n)))
                    .anyMatch(cand -> cand != null && RestrictionWords.mentionsRestriction(cand.sentText()));
            boolean supported = approval ? RestrictionWords.supportsApproval(c.quote())
                    : RestrictionWords.supportsEvidence(c.quote());
            if (citesRestrictive && !supported) {
                dropped.merge(Reason.NOT_SUPPORTED, 1, Integer::sum);
                continue;
            }
            if (!seen.add(squash(c.text()))) {
                continue;
            }
            kept.add(new Item(c.text(), ids(c.clauseNames(), byName), c.quote()));
        }
        return kept;
    }

    private static Reason problem(ParsedGuidance.Cited c, Map<String, Candidate> byName) {
        if (c.text().isBlank() || c.text().length() > MAX_TEXT_CHARS || c.quote().length() > MAX_QUOTE_CHARS) {
            return Reason.EMPTY_OR_LONG;
        }
        if (c.clauseNames().isEmpty()) {
            return Reason.NO_CLAUSE;
        }
        List<Candidate> cited = new ArrayList<>();
        for (String name : c.clauseNames()) {
            Candidate candidate = byName.get(normalizeName(name));
            if (candidate == null) {
                return Reason.OUT_OF_CANDIDATES;
            }
            cited.add(candidate);
        }
        if (hasVerdict(c.text())) {
            return Reason.VERDICT;
        }
        if (QuoteMatcher.length(c.quote()) < MIN_QUOTE_CHARS) {
            return Reason.BAD_QUOTE;
        }
        boolean found = cited.stream().anyMatch(candidate -> QuoteMatcher.isVerbatim(candidate.sentText(), c.quote()));
        return found ? null : Reason.BAD_QUOTE;
    }

    private static List<GuidanceResult.Conflict> conflicts(List<ParsedGuidance.Conflict> parsed,
                                                           Map<String, Candidate> byName, Map<Reason, Integer> dropped) {
        List<GuidanceResult.Conflict> kept = new ArrayList<>();
        for (ParsedGuidance.Conflict c : parsed) {
            if (c.description().isBlank() || c.description().length() > MAX_TEXT_CHARS) {
                dropped.merge(Reason.EMPTY_OR_LONG, 1, Integer::sum);
                continue;
            }
            if (c.clauseNames().isEmpty()) {
                dropped.merge(Reason.NO_CLAUSE, 1, Integer::sum);
                continue;
            }
            boolean allKnown = c.clauseNames().stream().allMatch(n -> byName.containsKey(normalizeName(n)));
            if (!allKnown) {
                dropped.merge(Reason.OUT_OF_CANDIDATES, 1, Integer::sum);
                continue;
            }
            List<Long> ids = ids(c.clauseNames(), byName);
            if (ids.size() < 2) {
                dropped.merge(Reason.ONE_CLAUSE_CONFLICT, 1, Integer::sum);
                continue;
            }
            if (hasVerdict(c.description())) {
                dropped.merge(Reason.VERDICT, 1, Integer::sum);
                continue;
            }
            kept.add(new GuidanceResult.Conflict(c.description(), ids));
        }
        return kept;
    }

    /** 같은 조항을 두 번 적었어도 한 번만. 순서는 모델이 적은 순서. */
    private static List<Long> ids(List<String> names, Map<String, Candidate> byName) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String name : names) {
            ids.add(byName.get(normalizeName(name)).clauseId());
        }
        return List.copyOf(ids);
    }

    /** "[C1]", "c1", " C1 " 처럼 적어도 같은 이름으로 본다. 이 외의 글(DB id 등)은 목록에 없으므로 걸러진다. */
    static String normalizeName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.length() > 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /** NFC 로 맞추고 모든 공백(줄바꿈 포함)을 없앤 글. 인용 비교와 판정 표현 찾기에 쓴다. */
    static String squash(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)).replaceAll("");
    }

    static boolean hasVerdict(String text) {
        String squashed = squash(text);
        return VERDICT_PHRASES.stream().anyMatch(squashed::contains);
    }
}
