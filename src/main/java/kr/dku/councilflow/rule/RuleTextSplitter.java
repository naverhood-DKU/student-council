package kr.dku.councilflow.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규정 글자를 "제n조" 단위로 나눈다. 순수 계산이라 스프링 없이 테스트한다.
 *
 * 원칙
 * - 조항 본문은 추출된 글자 그대로 둔다. 요약·교정·재서술을 하지 않는다.
 * - 정리하는 것은 쪽번호 줄("- 15 -")과 쪽 구분 문자(form feed)뿐이다.
 * - 나눈 조각을 "\n"으로 이어 붙이면 정리된 본문과 정확히 같아야 한다(round-trip). 같은지 결과에 담는다.
 * - 이 클래스의 결과는 후보일 뿐이다. 사람이 검수·확정하기 전에는 검색 대상이 아니다.
 */
public final class RuleTextSplitter {

    private static final Pattern HEAD =
            Pattern.compile("^제\\s*(\\d+)\\s*조(?:의\\s*(\\d+))?\\s*(?:\\(([^)\\n]*)\\)|\\s*삭제)");
    private static final Pattern CHAPTER = Pattern.compile("^제\\s*(\\d+)\\s*장\\s*(.*)$");
    private static final Pattern SECTION = Pattern.compile("^제\\s*(\\d+)\\s*절\\s*(.*)$");
    private static final Pattern SUPPLEMENTARY = Pattern.compile("^부\\s*칙\\s*$");
    private static final Pattern EXTRA = Pattern.compile("^보\\s*칙$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\s*-\\s*\\d+\\s*-\\s*$");
    private static final Pattern DELETED_MARK = Pattern.compile("<\\s*삭제");

    public static final int LONG_ARTICLE_CHARS = 4000;

    public enum Kind { PREAMBLE, CHAPTER, SECTION, ARTICLE, EXTRA, SUPPLEMENTARY }

    /** 나눈 조각 하나. 조항이 아닌 조각(장·절 제목, 부칙 등)도 순서대로 담는다. */
    public record Piece(Kind kind, String label, Integer number, Integer branch, String title,
                        String chapter, String section, String text, List<String> flags) {
    }

    public record Result(List<Piece> pieces, String body, boolean roundtripOk, int removedPageLines,
                         boolean bodyStartFound) {
    }

    private RuleTextSplitter() {
    }

    /** 쪽번호 줄과 form feed 만 걷어내고, 3줄 이상 이어진 빈 줄은 빈 줄 하나로 접는다. 제거한 줄 수를 함께 돌려준다. */
    public record Cleaned(String text, int removedPageLines) {
    }

    public static Cleaned clean(String raw) {
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\f', '\n');
        List<String> kept = new ArrayList<>();
        int removed = 0;
        for (String line : unified.split("\n", -1)) {
            if (PAGE_NUMBER.matcher(line).matches()) {
                removed++;
                continue;
            }
            kept.add(line);
        }
        String text = String.join("\n", kept).replaceAll("\\n{3,}", "\n\n");
        return new Cleaned(text, removed);
    }

    /**
     * @param raw       PDF 에서 뽑은 글자(또는 붙여 넣은 글자)
     * @param titleLine 문서 제목 한 줄. 목차를 건너뛰려고, 이 줄이 마지막으로 나오는 곳부터 본문으로 본다.
     *                  비어 있거나 못 찾으면 처음부터 본문으로 보고 bodyStartFound=false 로 알린다.
     */
    public static Result split(String raw, String titleLine) {
        Cleaned cleaned = clean(raw);
        String[] lines = cleaned.text().split("\n", -1);
        int bodyStart = 0;
        boolean found = false;
        if (titleLine != null && !titleLine.isBlank()) {
            String wanted = titleLine.strip();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].strip().equals(wanted)) {
                    bodyStart = i;
                    found = true;
                }
            }
        }
        List<String> body = new ArrayList<>(List.of(lines).subList(bodyStart, lines.length));
        String bodyText = String.join("\n", body);

        List<Mark> marks = findMarks(body);
        List<Piece> pieces = new ArrayList<>();
        String chapter = null;
        String section = null;
        int[] prev = {0, 0};
        int firstMark = marks.isEmpty() ? body.size() : marks.get(0).index();
        if (firstMark > 0 || marks.isEmpty()) {
            pieces.add(new Piece(Kind.PREAMBLE, "머리말", null, null, null, null, null,
                    join(body, 0, firstMark), List.of()));
        }
        for (int k = 0; k < marks.size(); k++) {
            Mark m = marks.get(k);
            int end = k + 1 < marks.size() ? marks.get(k + 1).index() : body.size();
            String text = join(body, m.index(), end);
            switch (m.kind()) {
                case CHAPTER -> {
                    chapter = ("제" + m.number() + "장 " + m.title()).strip();
                    section = null;
                    pieces.add(new Piece(Kind.CHAPTER, chapter, m.number(), null, m.title(), chapter, null, text, List.of()));
                }
                case SECTION -> {
                    section = ("제" + m.number() + "절 " + m.title()).strip();
                    pieces.add(new Piece(Kind.SECTION, section, m.number(), null, m.title(), chapter, section, text, List.of()));
                }
                case ARTICLE -> {
                    String label = "제" + m.number() + "조" + (m.branch() > 0 ? "의" + m.branch() : "");
                    List<String> flags = new ArrayList<>();
                    if (m.number() < prev[0] || (m.number() == prev[0] && m.branch() < prev[1])) {
                        flags.add("조 번호 순서가 거꾸로 됨: 제목줄 오인식 가능, 사람이 확인");
                    } else if (m.number() == prev[0] && m.branch() == prev[1]) {
                        flags.add("조 번호가 앞 조항과 중복");
                    }
                    prev = new int[] {m.number(), m.branch()};
                    boolean deleted = m.deletedHead() || "삭제".equals(m.title()) || DELETED_MARK.matcher(text).find();
                    if (deleted) {
                        flags.add("삭제된 조문");
                    }
                    if (text.strip().length() < label.length() + 2 && !m.deletedHead()) {
                        flags.add("본문이 거의 없음");
                    }
                    if (text.length() > LONG_ARTICLE_CHARS) {
                        flags.add("본문이 매우 김(" + LONG_ARTICLE_CHARS + "자 초과): 항 단위 분리 필요 여부 확인");
                    }
                    pieces.add(new Piece(Kind.ARTICLE, label, m.number(), m.branch(), m.title(), chapter, section, text, flags));
                }
                case EXTRA -> pieces.add(new Piece(Kind.EXTRA, "보칙", null, null, "보칙", chapter, section, text, List.of()));
                case SUPPLEMENTARY -> pieces.add(new Piece(Kind.SUPPLEMENTARY, "부칙", null, null, "부칙", null, null, text, List.of()));
                default -> throw new IllegalStateException(m.kind().name());
            }
        }
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) {
                rebuilt.append('\n');
            }
            rebuilt.append(pieces.get(i).text());
        }
        return new Result(pieces, bodyText, rebuilt.toString().equals(bodyText), cleaned.removedPageLines(), found);
    }

    private record Mark(int index, Kind kind, Integer number, int branch, String title, boolean deletedHead) {
    }

    private static List<Mark> findMarks(List<String> body) {
        List<Mark> marks = new ArrayList<>();
        boolean inSupplementary = false;
        for (int i = 0; i < body.size(); i++) {
            String s = body.get(i).strip();
            boolean splitSupplementary = false;
            if (s.equals("부")) { // PDF 추출이 "부\n\n칙"처럼 쪼개 놓는 경우
                int j = i + 1;
                while (j < body.size() && body.get(j).isBlank()) {
                    j++;
                }
                splitSupplementary = j < body.size() && body.get(j).strip().equals("칙");
            }
            if (SUPPLEMENTARY.matcher(s).matches() || splitSupplementary) {
                inSupplementary = true;
                marks.add(new Mark(i, Kind.SUPPLEMENTARY, null, 0, "부칙", false));
                continue;
            }
            if (!inSupplementary && EXTRA.matcher(s).matches()) {
                marks.add(new Mark(i, Kind.EXTRA, null, 0, "보칙", false));
                continue;
            }
            if (inSupplementary) {
                continue;
            }
            Matcher m = HEAD.matcher(s);
            if (m.find()) {
                boolean deletedHead = m.group(3) == null;
                String title = m.group(3) == null ? "삭제" : m.group(3).strip();
                int branch = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                marks.add(new Mark(i, Kind.ARTICLE, Integer.parseInt(m.group(1)), branch, title, deletedHead));
                continue;
            }
            m = CHAPTER.matcher(s);
            if (m.matches() && s.length() < 60) {
                marks.add(new Mark(i, Kind.CHAPTER, Integer.parseInt(m.group(1)), 0, m.group(2).strip(), false));
                continue;
            }
            m = SECTION.matcher(s);
            if (m.matches() && s.length() < 60) {
                marks.add(new Mark(i, Kind.SECTION, Integer.parseInt(m.group(1)), 0, m.group(2).strip(), false));
            }
        }
        return marks;
    }

    private static String join(List<String> lines, int from, int to) {
        return String.join("\n", lines.subList(from, to));
    }
}
