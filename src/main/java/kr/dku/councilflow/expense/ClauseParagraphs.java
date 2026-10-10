package kr.dku.councilflow.expense;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 조항 글을 문단(① ② … 또는 "1. " "가. " 으로 시작하는 줄)으로 나누고, 지출 항목과 글자가 겹치는 문단을 찾는다.
 * 순수 계산이다. 문단은 DB 의 조항 원문을 자른 것이라 모델이 쓴 글이 아니다.
 *
 * 쓰는 곳: 긴 조항에서 모델이 특정 문단(예: ⑥ 카카오톡 기프티콘)을 놓치는 일이 있어, 화면에서 사람이 그 문단을 바로 보게 하고
 * 모델에게도 "이 문단을 먼저 보라"고 알려 준다. 글자 조각이 겹치는지만 보므로 말이 달라 겹치지 않는 문단은 못 찾는다.
 */
final class ClauseParagraphs {

    /** 문단 시작: 줄 맨 앞의 ①~⑳, "1. ", "가. " */
    private static final Pattern START = Pattern.compile("(?m)^(?=[ \\t]*(?:[\u2460-\u2473]|\\d{1,2}\\.[ \\t]|[가-하]\\.[ \\t]))");
    static final int MAX_PARAGRAPHS = 3;
    static final int MAX_PARAGRAPH_CHARS = 600;

    private ClauseParagraphs() {
    }

    static List<String> split(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return paragraphs;
        }
        for (String part : START.split(text)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /**
     * 지출 항목(subject)과 글자 조각이 겹치는 문단. 문단이 하나뿐이면 조항 전체와 같으므로 빈 목록이다.
     * 흔한 조각(학생·회비·사용 …)만 겹치면 겹친 것으로 보지 않는다(RestrictionWords.relatedTo).
     */
    static List<String> overlapping(String text, String subject) {
        List<String> paragraphs = split(text);
        if (paragraphs.size() < 2 || subject == null || subject.isBlank()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (int i = 1; i < paragraphs.size() && matched.size() < MAX_PARAGRAPHS; i++) {
            // 첫 덩어리(제목 줄)는 조항 이름이라 문단으로 보지 않는다.
            String paragraph = paragraphs.get(i);
            if (RestrictionWords.relatedTo(paragraph, subject)) {
                matched.add(paragraph.length() > MAX_PARAGRAPH_CHARS
                        ? paragraph.substring(0, MAX_PARAGRAPH_CHARS) + "…" : paragraph);
            }
        }
        return matched;
    }
}
