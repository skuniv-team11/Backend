package kr.ac.skuniv.coopradar.job;

import java.util.Comparator;
import java.util.List;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.job.JobDetail.Evidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 직무 상세(#17). 시드만 읽는다. 통근 시간은 붙이지 않는다(#18, ADR-0007). */
@Service
public class JobService {

    private final JobRepository jobs;

    public JobService(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public JobDetail detail(long jobId) {
        var row = jobs.findJob(jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_NOT_FOUND, "없는 직무예요"));
        int institutionId = row.institution().id();
        List<Evidence> evidence = jobs.evidence(row.id(), institutionId).stream()
                .sorted(Comparator.comparingInt((Evidence e) -> EvidenceLabels.order(e.fieldKey())))
                .toList();
        return new JobDetail(row.id(), row.round(), row.institution(), row.team(), row.title(), row.overview(),
                row.educationGoal(), row.competencies(), jobs.weeklyPlan(row.id()), row.conditions(), row.requirements(),
                row.workplace(), row.closing(), evidence, jobs.alerts(row.id(), institutionId),
                jobs.seniorNotes(institutionId));
    }
}
