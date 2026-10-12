package kr.dku.councilflow.expense;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 모델이 옮긴 인용 구절이 근거 조항 원문에 실제로 있는지 확인한다. 순수 계산이다.
 *
 * 실제 규정(PDF 에서 뽑은 글)으로 평가해 보니 모델은 한 구절만 옮기지 않고, 목록 조항의 머리 문장과 해당 호를
 * 이어 붙이는 일이 많았다("다음 각 호에 대한 학생회비 사용을 금지한다. 2. 학생회 LT"). 중간 호를 건너뛰므로
 * 원문에서 연속된 한 구절이 아니다. 그래서 다음 규칙으로 확인한다.
 *
 * - 인용을 문장 끝, 번호 머리("2."), 동그라미 숫자(①), 말줄임표에서 조각으로 나눈다.
 * - 모든 조각이 원문에 "순서대로" 그대로 있어야 한다. 한 조각이라도 없으면 지어낸 것으로 본다.
 * - 비교는 글자와 숫자만 남기고 한다(공백, 따옴표, 괄호, 문장부호, 동그라미 숫자 차이는 무시. NFC 정규화).
 * - 조각을 모두 합쳐 글자 수가 MIN_CHARS 이상이어야 한다.
 *
 * 한계: 서로 다른 문장을 순서만 지켜 이어 붙이는 것은 막지 못한다. 각 조각이 원문 그대로라는 것까지만 확인한다.
 */
final class QuoteMatcher {

    static final int MIN_CHARS = ExpenseGuidanceValidator.MIN_QUOTE_CHARS;

    /** 조각 사이의 경계: 문장 끝 뒤 공백, 줄머리 번호("2. ") 앞 공백, 동그라미 숫자, 말줄임표 */
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?<=[.!?。])\\s+|\\s+(?=\\d{1,2}\\.\\s)|[\\u2460-\\u2473\\u2474-\\u2487\\u24F5-\\u24FE]|\\.{3}|\\u2026");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}0-9]+");

    private QuoteMatcher() {
    }

    /** 글자와 숫자만 남긴 비교용 글 */
    static String loose(String text) {
        return NOT_LETTER_OR_DIGIT.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)).replaceAll("");
    }

    /** 인용을 비교용 조각으로 나눈다. 글자·숫자가 둘 미만인 조각(번호 머리 등)은 버린다. */
    static List<String> fragments(String quote) {
        List<String> result = new ArrayList<>();
        for (String piece : BOUNDARY.split(quote == null ? "" : quote)) {
            String loose = loose(piece);
            if (loose.codePointCount(0, loose.length()) >= 2) {
                result.add(loose);
            }
        }
        return result;
    }

    /** 인용을 이루는 글자 수(조각 합계). 최소 길이 검사에 쓴다. */
    static int length(String quote) {
        int total = 0;
        for (String fragment : fragments(quote)) {
            total += fragment.codePointCount(0, fragment.length());
        }
        return total;
    }

    /** 모든 조각이 원문에 순서대로 그대로 있으면 true */
    static boolean isVerbatim(String clauseText, String quote) {
        List<String> fragments = fragments(quote);
        if (fragments.isEmpty()) {
            return false;
        }
        String haystack = loose(clauseText);
        int from = 0;
        for (String fragment : fragments) {
            int at = haystack.indexOf(fragment, from);
            if (at < 0) {
                return false;
            }
            from = at + fragment.length();
        }
        return true;
    }
}
