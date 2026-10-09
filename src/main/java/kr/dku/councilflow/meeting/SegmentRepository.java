package kr.dku.councilflow.meeting;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SegmentRepository extends JpaRepository<Segment, Long> {

    List<Segment> findByMeetingIdAndTranscriptVersionOrderBySeq(Long meetingId, int transcriptVersion);

    long countByMeetingIdAndTranscriptVersion(Long meetingId, int transcriptVersion);

    List<Segment> findByIdIn(Collection<Long> ids);
}
