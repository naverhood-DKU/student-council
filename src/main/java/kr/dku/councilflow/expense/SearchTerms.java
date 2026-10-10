package kr.dku.councilflow.expense;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 후보 조항을 찾을 검색어를 만든다. MySQL FULLTEXT ngram(글자 2개 단위) 자연어 검색에 넘길 글자만 남긴다.
 * 순수 계산이라 스프링 없이 테스트한다.
 *
 * - 한글·영문·숫자만 남기고 나머지 기호는 공백으로 바꾼다(검색 연산자가 섞여 들어가는 것도 막는다).
 * - ngram 은 글자 2개 단위라 1글자 낱말은 검색에 보탬이 안 되므로 버린다.
 * - 같은 낱말은 한 번만 쓴다. 전체는 MAX_CHARS 자로 자른다.
 */
public final class SearchTerms {

    public static final int MAX_CHARS = 300;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{N}]+");

    private SearchTerms() {
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
