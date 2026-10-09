package kr.dku.councilflow.org;

import java.util.List;

/** 새 그룹을 만들 때 기본으로 넣는 부서. role_keywords 는 AI 담당 부서 추천의 근거다. */
public final class DefaultDepartments {

    private DefaultDepartments() {
    }

    public static List<Department> of(Organization organization) {
        return List.of(
                new Department(organization, "기획부", "기획,행사,일정,장소,섭외"),
                new Department(organization, "홍보부", "홍보,SNS,공지,포스터,카드뉴스"),
                new Department(organization, "재정부", "예산,회계,지출,영수증,정산,학생회비"));
    }
}
