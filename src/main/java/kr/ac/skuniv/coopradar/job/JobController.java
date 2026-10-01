package kr.ac.skuniv.coopradar.job;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** docs/api #17 직무 상세 + AI 추출 근거. 로그인한 사람이면 역할과 상관없다. 없는 직무는 404 JOB_NOT_FOUND. */
@RestController
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/api/jobs/{jobId}")
    public JobDetail detail(@PathVariable long jobId) {
        return jobs.detail(jobId);
    }
}
