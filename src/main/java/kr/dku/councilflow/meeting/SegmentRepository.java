package kr.dku.councilflow.meeting;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SegmentRepository extends JpaRepository<Segment, Long> {

    List<Segment> findByMeetingIdAndTranscriptVersionOrderBySeq(Long meetingId, int transcriptVersion);

    long countByMeetingIdAndTranscriptVersion(Long meetingId, int transcriptVersion);

    List<Segment> findByIdIn(Collection<Long> ids);

    /** 회의를 지울 때 모든 전사 버전의 발언을 지운다. */
    @Modifying
    @Query("delete from Segment s where s.meetingId = :meetingId")
    int deleteByMeetingId(Long meetingId);
}
