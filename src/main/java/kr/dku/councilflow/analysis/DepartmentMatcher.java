package kr.dku.councilflow.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import kr.dku.councilflow.org.Department;

/**
 * 회의에서 말한 담당 이름을 등록된 부서와 맞춰 본다.
 * - exact: 이름이 정확히 같을 때만(공백 무시) 담당으로 인정한다.
 * - suggest: 부서 이름과 역할 키워드가 글에 몇 개 나오는지 세어 가장 많은 부서 하나를 "추천"한다. 동점이면 추천하지 않는다.
 */
public final class DepartmentMatcher {

    private DepartmentMatcher() {
    }

    public static Optional<Department> exact(String name, List<Department> departments) {
        String wanted = compact(name);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return departments.stream().filter(d -> compact(d.getName()).equals(wanted)).findFirst();
    }

    public static Optional<Department> suggest(String text, List<Department> departments) {
        String haystack = compact(text);
        if (haystack.isEmpty()) {
            return Optional.empty();
        }
        Department best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Department department : departments) {
            int score = score(haystack, department);
            if (score > bestScore) {
                best = department;
                bestScore = score;
                tie = false;
            } else if (score == bestScore && score > 0) {
                tie = true;
            }
        }
        return tie ? Optional.empty() : Optional.ofNullable(best);
    }

    private static int score(String haystack, Department department) {
        int score = 0;
        String stem = compact(department.getName()).replaceAll("(부|팀|국)$", "");
        if (!stem.isEmpty() && haystack.contains(stem)) {
            score += 2;
        }
        if (department.getRoleKeywords() != null) {
            score += (int) Arrays.stream(department.getRoleKeywords().split(","))
                    .map(DepartmentMatcher::compact)
                    .filter(k -> !k.isEmpty() && haystack.contains(k))
                    .count();
        }
        return score;
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s", "");
    }
}
