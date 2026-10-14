package kr.dku.councilflow.expense;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 후보 조항을 찾을 검색어를 만든다. MySQL FULLTEXT ngram(글자 2개 단위) 자연어 검색에 넘길 글자만 남긴다.
 * 순수 계산이라 스프링 없이 테스트한다.
 *
 * - 한글·영문·숫자만 남기고 나머지 기호는 공백으로 바꾼다(검색 연산자가 섞여 들어가는 것도 막는다).
 * - ngram 은 글자 2개 단위라 1글자 낱말은 검색에 보탬이 안 되므로 버린다.
 * - 같은 낱말은 한 번만 쓴다. 전체는 MAX_CHARS 자로 자른다.
 * - 음성 인식(전사)은 영문 약어를 한글 소리 그대로 적는 일이 많다("LT" → "엘티"). 규정에는 "LT"로 적혀 있으므로
 *   자주 나오는 소리 표기를 규정의 표기로 바꾼 낱말을 덧붙인다(원래 낱말은 그대로 둔다).
 */
public final class SearchTerms {

    public static final int MAX_CHARS = 300;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{N}]+");

    /** 전사에서 자주 틀리게 적히는 말 → 규정에 쓰인 표기. 낱말 안에 들어 있으면(엘티가, 엘티는) 덧붙인다. */
    private static final Map<String, String> SPEECH_ALIASES = new LinkedHashMap<>();

    static {
        SPEECH_ALIASES.put("엘티", "LT");
        SPEECH_ALIASES.put("엠티", "MT");
        SPEECH_ALIASES.put("오티", "OT");
        SPEECH_ALIASES.put("기프트콘", "기프티콘");
    }

    private SearchTerms() {
    }

    /**
     * 금액을 규정에서 흔히 쓰는 한국식 표기로 바꾼 검색어. 규정에는 "30만원 이상"처럼 쓰이므로 300000 → "30만원".
     * 금액이 없거나 0 이하면 null. 숫자만 늘어놓은 표기("300000원")는 ngram 으로 아무 숫자나 걸려서 쓰지 않는다.
     *
     * 예) 300000 → 30만원 / 15000 → 1만5천원 / 5000 → 5천원 / 120000000 → 1억2000만원
     * 천 원 단위로 떨어지지 않는 나머지는 버리고 만 원 단위로 쓴다(규정의 기준 금액은 대개 딱 떨어진다).
     */
    public static String amountTerms(Long amount) {
        if (amount == null || amount <= 0) {
            return null;
        }
        long eok = amount / 100_000_000L;
        long rest = amount % 100_000_000L;
        long man = rest / 10_000L;
        long remainder = rest % 10_000L;
        String form;
        if (eok > 0) {
            form = man == 0 ? eok + "억원" : eok + "억" + man + "만원";
        } else if (man > 0) {
            form = remainder != 0 && remainder % 1000 == 0
                    ? man + "만" + (remainder / 1000) + "천원"
                    : man + "만원";
        } else if (remainder % 1000 == 0) {
            form = (remainder / 1000) + "천원";
        } else {
            return null;
        }
        return build(form);
    }

    /** 검색어를 만든다. 쓸 만한 낱말이 하나도 없으면 null. */
    public static String build(String... parts) {
        Set<String> words = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String cleaned = NOT_WORD.matcher(Normalizer.normalize(part, Normalizer.Form.NFC)).replaceAll(" ").strip();
            for (String word : cleaned.split("\\s+")) {
                if (word.codePointCount(0, word.length()) >= 2) {
                    words.add(word);
                }
            }
            SPEECH_ALIASES.forEach((spoken, written) -> {
                if (cleaned.contains(spoken)) {
                    words.add(written);
                }
            });
        }
        if (words.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() + word.length() + 1 > MAX_CHARS) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word);
        }
        return out.isEmpty() ? null : out.toString();
    }
}
