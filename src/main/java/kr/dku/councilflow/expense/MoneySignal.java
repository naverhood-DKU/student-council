package kr.dku.councilflow.expense;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 업무 카드가 돈과 관련되어 있는지 가리는 서버의 낱말 검사. 규정을 자동으로 확인할 카드를 고르는 데만 쓴다.
 *
 * - 카드 글(제목·담당 표현)에 지출·결제·구매 같은 낱말이나 금액 표기가 있으면 돈 관련이다.
 * - 담당 부서가 재정·총무·사무·회계 같은 이름이면 돈 관련으로 본다(그 부서 카드는 규정 확인 표시를 항상 보여 준다).
 * - 의미를 이해하지 못하는 검사라 놓치거나 잘못 잡을 수 있다. 잡힌 카드도 규정에서 관련 조항이 없으면 배지를 달지 않는다.
 */
final class MoneySignal {

    private static final List<String> MONEY_WORDS = List.of(
            "구매", "구입", "결제", "비용", "지출", "예산", "학생회비", "회비", "환급", "환불", "정산", "영수증",
            "지원금", "후원", "협찬", "대금", "경비", "숙박", "식비", "회식", "뒤풀이", "간식", "다과", "기프티콘",
            "선결제", "사비", "카드", "현금", "계좌", "이체", "입금", "송금", "인출", "대여", "렌트", "제작비",
            "인쇄", "경품", "상품", "물품", "견적", "발주", "주문", "사전답사", "엠티", "MT", "LT", "워크숍");

    /** 30만원, 5,000원, 12000 원 */
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*\\s*(만\\s*)?(천\\s*)?원");

    private static final List<String> FINANCE_DEPARTMENT_WORDS = List.of("재정", "총무", "사무", "회계", "경리", "예산");

    private MoneySignal() {
    }

    /** 글 중 하나라도 돈과 관련된 낱말이나 금액 표기가 있으면 true */
    static boolean mentionsMoney(String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // "카드뉴스"는 결제 카드가 아니다.
            String plain = text.replace("카드뉴스", "");
            if (AMOUNT.matcher(plain).find()) {
                return true;
            }
            for (String word : MONEY_WORDS) {
                if (plain.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 재정·총무·사무 같은 돈을 다루는 부서인가. 이름과 역할 키워드를 본다. */
    static boolean isFinanceDepartment(String name, String roleKeywords) {
        for (String text : new String[]{name, roleKeywords}) {
            if (text == null) {
                continue;
            }
            for (String word : FINANCE_DEPARTMENT_WORDS) {
                if (text.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }
}
