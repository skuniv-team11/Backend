package kr.ac.skuniv.coopradar.job;

import java.util.Comparator;
import java.util.List;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.auth.Role;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MyEligibility;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService;
import kr.ac.skuniv.coopradar.job.JobDetail.Evidence;
import kr.ac.skuniv.coopradar.job.JobViewService.JobViews;
import kr.ac.skuniv.coopradar.me.ProfileService;
import kr.ac.skuniv.coopradar.plan.PlanRepository;
import kr.ac.skuniv.coopradar.plan.PlanDtos.PlanItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직무 상세(#17). 시드를 읽고, 보는 사람의 조회 수·담기·지망 순위·판정(저장한 프로필이 있을 때)을 붙인다(ADR-0035).
 * 통근 시간은 붙이지 않는다(#18, ADR-0007).
 */
@Service
public class JobService {

    private final JobRepository jobs;
    private final JobViewService views;
    private final PlanRepository plans;
    private final ProfileService profiles;
    private final EligibilityService eligibility;

    public JobService(JobRepository jobs, JobViewService views, PlanRepository plans, ProfileService profiles,
                      EligibilityService eligibility) {
        this.jobs = jobs;
        this.views = views;
        this.plans = plans;
        this.profiles = profiles;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true)
    public boolean exists(long jobId) {
        return jobs.exists(jobId);
    }

    @Transactional(readOnly = true)
    public JobDetail detail(AuthUser user, long jobId) {
        var row = jobs.findJob(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요"));
        int institutionId = row.institution().id();
        List<Evidence> evidence = jobs.evidence(row.id(), institutionId).stream()
                .sorted(Comparator.comparingInt((Evidence e) -> EvidenceLabels.order(e.fieldKey())))
                .toList();
        JobViews count = views.views(row.id());
        boolean student = user.role() == Role.STUDENT;
        PlanItem planned = student ? plans.items(user.id()).stream().filter(p -> p.jobId() == row.id()).findFirst()
                .orElse(null) : null;
        MyEligibility mine = student ? profiles.savedInput(user)
                .flatMap(p -> eligibility.judgeOne(row.round().id(), p, row.id()))
                .map(MyEligibility::of).orElse(null) : null;
        return new JobDetail(row.id(), row.round(), row.institution(), row.team(), row.title(), row.overview(),
                row.educationGoal(), row.competencies(), jobs.weeklyPlan(row.id()), row.conditions(),
                row.requirements().withMajorAliases(jobs.majorAliases(row.id())),
                row.workplace(), row.closing(), count.views(), count.todayViews(), planned != null,
                planned == null ? null : planned.rank(), mine, evidence, jobs.alerts(row.id(), institutionId),
                jobs.seniorNotes(institutionId), jobs.photos(institutionId));
    }
}
