package kr.dku.councilflow.analysis;

import java.time.LocalDate;
import java.util.List;

/** 분석에 필요한 회의 내용을 DB 트랜잭션 밖으로 꺼낸 사본. AI 호출 동안 DB 연결을 쥐지 않기 위함. */
public record MeetingSnapshot(
        long meetingId,
        String title,
        LocalDate heldDate,
        int transcriptVersion,
        List<DepartmentInfo> departments,
        List<Line> lines) {

    /** 등록된 부서 이름과 역할 키워드. AI가 업무 내용을 보고 담당 부서를 고를 때 쓴다. */
    public record DepartmentInfo(String name, String roleKeywords) {
    }

    /** label 은 AI가 인용하는 발언 ID(S12). */
    public record Line(String label, String speaker, String text) {
    }
}
