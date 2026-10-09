package kr.dku.councilflow.org;

import java.util.List;
import kr.dku.councilflow.user.MembershipRole;

/** 새 그룹을 만들 때 기본으로 넣는 직책. 회장이 직책 설정에서 더하거나 고친다. */
public final class DefaultPositions {

    private DefaultPositions() {
    }

    public static List<Position> of(Organization organization) {
        return List.of(
                new Position(organization, "부장", MembershipRole.EDITOR, 1),
                new Position(organization, "부원", MembershipRole.VIEWER, 2));
    }
}
