package kr.ac.skuniv.coopradar.job;

import kr.ac.skuniv.coopradar.auth.AuthUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/api #17 직무 상세 + AI 추출 근거. 로그인한 사람이면 역할과 상관없다. 없는 직무는 404 JOB_NOT_FOUND.
 * 학생이 #17을 열면 조회수에 하루 한 번 센다(ADR-0019). 조회 수는 #17·#14·#24 응답에 들어 있다(#25는 ADR-0036에서 지움).
 */
@RestController
public class JobController {

    private final JobService jobs;
    private final JobViewService views;

    public JobController(JobService jobs, JobViewService views) {
        this.jobs = jobs;
        this.views = views;
    }

    /** 조회를 먼저 센 뒤(없는 직무면 세지 않는다) 상세를 만든다 — 응답의 views에 이번 조회가 들어간다. */
    @GetMapping("/api/jobs/{jobId}")
    public JobDetail detail(AuthUser user, @PathVariable long jobId) {
        if (jobs.exists(jobId)) {
            views.record(jobId, user);
        }
        return jobs.detail(user, jobId);
    }
}
