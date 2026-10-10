package kr.ac.skuniv.coopradar.internship;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.ApprovalRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.JobInfo;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.PickRow;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalKind;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalPick;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalPlacement;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStatus;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalStudent;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.ApprovalView;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.me.ProfileRepository;
import kr.ac.skuniv.coopradar.me.SavedProfile;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학과(부)장 승인 링크(#42·#43, 공개, ADR-0033). 링크의 토큰이 곧 권한이다(128비트 무작위). 메일은 보내지 않고 학생(지원서)·
 * 센터(학점 인정)가 학과 사무실에 링크를 전한다. 보여 주는 것은 승인에 필요한 것만 — 이름·학번·학과·학년·지망(또는 실습 자리).
 * 연락처·주소·자기소개서는 보여 주지 않는다.
 */
@Service
public class ApprovalService {

    private final ApplicationRepository repo;
    private final ProfileRepository profiles;
    private final RoundService rounds;
    private final Clock clock;

    public ApprovalService(ApplicationRepository repo, ProfileRepository profiles, RoundService rounds, Clock clock) {
        this.repo = repo;
        this.profiles = profiles;
        this.rounds = rounds;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApprovalView get(String token) {
        return view(find(token));
    }

    @Transactional
    public ApprovalView approve(String token) {
        Found f = find(token);
        if (status(f) == ApprovalStatus.STALE) {
            throw new ApiException(ErrorCode.STATE_CONFLICT, "승인을 요청한 뒤 지원서가 바뀌었어요. 학생에게 새 링크를 받아 주세요");
        }
        repo.approve(token, clock.instant());
        return view(find(token));
    }

    private record Found(ApprovalRow approval, Row application, List<Integer> pickIds) {
    }

    private Found find(String token) {
        if (token == null || !token.matches("^[0-9a-f]{32}$")) {
            throw new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "승인 링크가 맞지 않아요");
        }
        ApprovalRow a = repo.approvalByToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.APPROVAL_NOT_FOUND, "승인 링크가 맞지 않거나 새 링크로 바뀌었어요"));
        Row r = repo.byId(a.applicationId()).orElseThrow();
        List<Integer> ids = ApplicationViews.editable(r.status()) && r.userId() != null ? repo.rankedPlan(r.userId())
                : repo.picks(List.of(r.id())).getOrDefault(r.id(), List.of()).stream().map(PickRow::jobId).toList();
        return new Found(a, r, ids);
    }

    private static ApprovalStatus status(Found f) {
        return f.approval().kind() == ApprovalKind.APPLICATION
                ? ApplicationViews.approvalStatus(f.approval(), ApplicationViews.contentHash(f.application(), f.pickIds()))
                : ApplicationViews.approvalStatus(f.approval(), null);
    }

    private ApprovalView view(Found f) {
        Row r = f.application();
        var round = rounds.current();
        SavedProfile p = r.departmentId() == null && r.userId() != null
                ? profiles.findSaved(r.userId(), true).orElse(null) : null;
        ApprovalStudent student = new ApprovalStudent(r.applicant().nameKo(), r.applicant().studentNo(),
                r.departmentId() != null ? r.department() : p == null ? null : p.department(),
                r.grade() != null ? r.grade() : p == null ? null : p.grade());
        Map<Integer, JobInfo> jobs = repo.jobs(f.pickIds());
        List<ApprovalPick> picks = List.of();
        ApprovalPlacement placement = null;
        if (f.approval().kind() == ApprovalKind.APPLICATION) {
            picks = new java.util.ArrayList<>();
            for (int i = 0; i < f.pickIds().size(); i++) {
                JobInfo j = jobs.get(f.pickIds().get(i));
                picks.add(new ApprovalPick(i + 1, j.title(), j.institution()));
            }
        } else if (r.matchedRank() != null && r.matchedRank() <= f.pickIds().size()) {
            JobInfo j = jobs.get(f.pickIds().get(r.matchedRank() - 1));
            placement = new ApprovalPlacement(j.title(), j.team(), j.institution(), j.periodStart(), j.periodEnd());
        }
        return new ApprovalView(f.approval().kind(), status(f), f.approval().requestedAt(), f.approval().approvedAt(),
                student, new RoundRef(round.id(), round.termCode()), picks, placement);
    }
}
