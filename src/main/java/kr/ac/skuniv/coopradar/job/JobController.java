package kr.ac.skuniv.coopradar.job;

import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.job.JobViewService.JobViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #17 직무 상세 + AI 추출 근거, #25 직무 조회수. 로그인한 사람이면 역할과 상관없다. 없는 직무는 404 JOB_NOT_FOUND.
 * 학생이 #17을 열면 조회수에 하루 한 번 센다(ADR-0019).
 */
@RestController
public class JobController {

    private final JobService jobs;
    private final JobViewService views;

    public JobController(JobService jobs, JobViewService views) {
        this.jobs = jobs;
        this.views = views;
    }

    @GetMapping("/api/jobs/{jobId}")
    public JobDetail detail(AuthUser user, @PathVariable long jobId) {
        JobDetail detail = jobs.detail(jobId);
        views.record(jobId, user);
        return detail;
    }

    @GetMapping("/api/jobs/{jobId}/views")
    public JobViews views(@PathVariable long jobId) {
        return views.views(jobId);
    }
}
