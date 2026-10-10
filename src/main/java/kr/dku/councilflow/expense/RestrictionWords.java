package kr.dku.councilflow.expense;

import java.util.List;

/**
 * 조항 글에서 "사용을 금지·제한한다"는 표현을 찾는 단순한 낱말 검사. 순수 계산이다.
 * AI 가 아니라 서버가 하는 확인이라서 의미를 이해하지는 못한다. 그래서 두 곳에만 쓴다.
 *
 * 1. 근거 조항에 금지·제한 표현이 있는지 화면에 알린다(승인·증빙 항목이 금지 조항을 근거로 들었는지 사람이 보게).
 * 2. AI 가 승인·증빙으로 분류한 항목의 인용 구절 자체가 금지·제한을 말하고 승인·확인 표현이 없으면, 서버가 금지·제한으로 옮긴다.
 *    (인용 구절은 서버가 원문에서 확인한 글이므로, 옮길 때는 AI 의 문구 대신 인용 구절을 쓴다.)
 *
 * 한계: 목록 조항("다음 각 호의 사용을 금지한다. 1. 학생회 회식 2. 학생회 LT")에서 호만 인용하면 "금지"가 구절에 없어
 * 2번은 잡지 못한다. 그때도 1번 알림은 조항 전체를 보므로 뜬다.
 */
final class RestrictionWords {

    private static final List<String> RESTRICTIVE = List.of(
            "금지", "불가", "제외한다", "제외하", "할수없", "못한다", "넘지못", "초과할수없", "허용하지",
            "아니된다", "안된다", "제한한다", "제한된다");

    private static final List<String> LIMIT_HINTS = List.of("넘지못", "초과", "이하", "이내", "한도", "최대", "까지만");

    private static final List<String> APPROVING = List.of(
            "승인", "결재", "허가", "협의", "의결", "동의", "확인을받", "확인한", "보고", "제출", "첨부", "신청");

    /**
     * 학생회 규정에서 거의 모든 조항에 나오는 흔한 글자 조각. 지출 항목과 금지 항목이 이것만 공유하면 관련 있다고 보지 않는다.
     * (예: "학생회 LT" 금지가 "학생회 회식비" 지출과 "학생회" 때문에 관련 있어 보이는 것을 막는다)
     */
    private static final java.util.Set<String> GENERIC_BIGRAMS = java.util.Set.of(
            "학생", "생회", "회비", "사용", "금지", "지출", "제외", "행사", "집행", "경비", "비용", "자치", "치기", "기구",
            "대한", "해당", "이외", "이상", "원칙", "다음", "각호", "증빙");

    private static final java.util.regex.Pattern TOKEN_SPLIT = java.util.regex.Pattern.compile("[^\\p{L}0-9]+");

    private RestrictionWords() {
    }

    /**
     * 금지·제한 항목(text)이 지출(subject: 항목·설명·찾을 말)과 직접 관련 있어 보이는가.
     * 낱말 안의 두 글자 조각이 하나라도 같으면 관련 있다고 본다(흔한 조각 제외). 의미를 이해하는 검사가 아니다.
     * 관련 없어 보이는 항목도 지우지 않고 따로 보여 준다. subject 가 없으면 모두 관련 있다고 본다.
     */
    static boolean relatedTo(String text, String subject) {
        if (subject == null || subject.isBlank()) {
            return true;
        }
        java.util.Set<String> wanted = bigrams(subject);
        wanted.removeAll(GENERIC_BIGRAMS);
        java.util.Set<String> found = bigrams(text);
        found.removeAll(GENERIC_BIGRAMS);
        found.retainAll(wanted);
        return !found.isEmpty();
    }

    /** 낱말(글자·숫자 덩어리) 안의 두 글자 조각. 낱말 경계를 넘는 조각은 만들지 않는다. */
    private static java.util.Set<String> bigrams(String text) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        String normalized = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT);
        for (String token : TOKEN_SPLIT.split(normalized)) {
            for (int i = 0; i + 2 <= token.length(); i++) {
                result.add(token.substring(i, i + 2));
            }
        }
        return result;
    }

    /** 인용 구절이 "승인·허락·확인"을 말하고 있다고 볼 낱말 */
    private static final List<String> APPROVAL_VOCABULARY = List.of(
            "승인", "결재", "허가", "협의", "의결", "동의", "인준", "확인", "심의", "결정", "허락", "보고", "인정");

    /** 인용 구절이 "제출·첨부해야 하는 서류·자료"를 말하고 있다고 볼 낱말 */
    private static final List<String> EVIDENCE_VOCABULARY = List.of(
            "제출", "첨부", "서류", "증빙", "영수증", "계획서", "보고서", "신청서", "명단", "내역", "대장", "사본", "원본",
            "자료", "수령증", "증명", "목록", "통장", "견적서", "양식", "기록부", "장부", "확인서", "공문");

    /**
     * 인용 구절에 필요한 승인(또는 증빙)을 말하는 낱말이 하나라도 있는가.
     * 목록 조항의 호만 떼어 인용해("2. 학생회 LT") 금지 조항을 승인으로 뒤집는 경우를 거르는 데 쓴다.
     */
    static boolean supportsApproval(String quote) {
        String squashed = ExpenseGuidanceValidator.squash(quote);
        return APPROVAL_VOCABULARY.stream().anyMatch(squashed::contains);
    }

    static boolean supportsEvidence(String quote) {
        String squashed = ExpenseGuidanceValidator.squash(quote);
        return EVIDENCE_VOCABULARY.stream().anyMatch(squashed::contains);
    }

    /** 글에 금지·제한 표현이 하나라도 있으면 true */
    static boolean mentionsRestriction(String text) {
        String squashed = ExpenseGuidanceValidator.squash(text);
        return RESTRICTIVE.stream().anyMatch(squashed::contains);
    }

    /** 금지·제한을 말하고 승인·확인·제출 표현은 없는 구절인가(예외로 승인을 받으면 허용하는 조항은 해당 없음) */
    static boolean isPlainRestriction(String quote) {
        String squashed = ExpenseGuidanceValidator.squash(quote);
        boolean restrictive = RESTRICTIVE.stream().anyMatch(squashed::contains);
        boolean approving = APPROVING.stream().anyMatch(squashed::contains);
        return restrictive && !approving;
    }

    /** 한도·조건을 두는 말이면 LIMITED, 아니면 PROHIBITED */
    static String kindOf(String quote) {
        String squashed = ExpenseGuidanceValidator.squash(quote);
        boolean prohibits = squashed.contains("금지") || squashed.contains("불가") || squashed.contains("제외")
                || squashed.contains("할수없");
        boolean limits = LIMIT_HINTS.stream().anyMatch(squashed::contains);
        return limits && !prohibits ? GuidanceResult.LIMITED : GuidanceResult.PROHIBITED;
    }
}
