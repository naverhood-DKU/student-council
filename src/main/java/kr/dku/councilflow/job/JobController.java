package kr.dku.councilflow.job;

import kr.dku.councilflow.auth.CurrentUser;
import kr.dku.councilflow.auth.OrgAccessGuard;
import kr.dku.councilflow.common.error.ApiException;
import kr.dku.councilflow.common.error.ErrorCode;
import kr.dku.councilflow.meeting.MeetingRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobController {

    private final JobRepository jobs;
    private final MeetingRepository meetings;
    private final OrgAccessGuard accessGuard;

    JobController(JobRepository jobs, MeetingRepository meetings, OrgAccessGuard accessGuard) {
        this.jobs = jobs;
        this.meetings = meetings;
        this.accessGuard = accessGuard;
    }

    /** 작업 상태. 작업 대상(회의)이 속한 조직의 소속만 볼 수 있다. */
    @GetMapping("/api/jobs/{jobId}")
    @Transactional(readOnly = true)
    public JobView get(CurrentUser user, @PathVariable long jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        long orgId = switch (job.getType()) {
            case ANALYZE, TRANSCRIBE -> meetings.findById(job.getTargetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN))
                    .getOrgId();
            case EXPENSE -> throw new ApiException(ErrorCode.FORBIDDEN);
        };
        accessGuard.requireMember(user, orgId);
        return JobView.of(job);
    }
}
