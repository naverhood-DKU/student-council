package kr.dku.councilflow.user;

/**
 * 그룹 안의 역할. 총학·단과대·학과 구분 없이 모든 그룹이 같은 세 역할을 쓴다. 한 그룹에서 한 사람은 역할 하나.
 * 서비스 관리자는 역할이 아니라 계정 속성(AppUser.serviceAdmin)이다.
 */
public enum MembershipRole {
    /** 회장: 그룹의 모든 기능. 회의 올리기·승인, 업무 편집, 다른 그룹에 업무 요청·결과 승인, 구성원 관리 */
    OWNER,
    /** 부장: 자기 부서 업무와 받은 요청(담당 부서일 때)의 시작·결과 제출 */
    EDITOR,
    /** 부원: 조회만 */
    VIEWER
}
