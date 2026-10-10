package kr.ac.skuniv.coopradar.center;

import java.util.ArrayList;
import java.util.List;
import kr.ac.skuniv.coopradar.auth.AuthUser;
import kr.ac.skuniv.coopradar.center.CenterDtos.CenterBoard;
import kr.ac.skuniv.coopradar.center.CenterDtos.Risk;
import kr.ac.skuniv.coopradar.center.CenterDtos.RiskCode;
import kr.ac.skuniv.coopradar.center.CenterDtos.Row;
import kr.ac.skuniv.coopradar.center.CenterDtos.Summary;
import kr.ac.skuniv.coopradar.job.Alert;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.CodeLabels;
import kr.ac.skuniv.coopradar.reference.ReferenceDates;
import kr.ac.skuniv.coopradar.reference.ReferenceDates.AsOf;
import kr.ac.skuniv.coopradar.reference.RoundService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 현황판(#24). 직무별 정원·조건 위험·문서 검토와, 처리할 것(새 지원서·보완 요청)·학생이 찾는 직무(직무 탐색 1~3위 →
 * NCS 세분류)를 보여 준다. 위험 요인은 규칙이다(AI 없음). 처리할 것·학생이 찾는 직무는 계정의 범위(체험 묶음 또는 실제)로
 * 센다(ADR-0033). 관심(담은 수)은 지원이 아니라서 보여 주지 않는다(ADR-0036). 마감은 모집 판정 기준일로 본다(ADR-0035).
 */
@Service
public class CenterService {

    private final CenterRepository repository;
    private final RoundService rounds;
    private final CenterProperties properties;

    public CenterService(CenterRepository repository, RoundService rounds, CenterProperties properties) {
        this.repository = repository;
        this.rounds = rounds;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public CenterBoard board(AuthUser user) {
        var round = rounds.current();
        AsOf asOf = ReferenceDates.recruit(round);
        List<Alert> alerts = repository.alerts(round.id());

        List<Row> rows = new ArrayList<>();
        int seats = 0;
        int closed = 0;
        for (var job : repository.jobs(round.id())) {
            int alertCount = (int) alerts.stream()
                    .filter(a -> a.institution().id() == job.institution().id()
                            && (a.jobId() == null || a.jobId() == job.id()))
                    .count();
            boolean isClosed = asOf.closed(job.closing().closesOn());
            rows.add(new Row(job.id(), job.institution(), job.title(), job.headcount(), job.closing(), isClosed,
                    job.eligiblePool(), risks(job, alertCount, properties.narrowPoolBelow()), alertCount, job.views()));
            seats += job.headcount();
            closed += isClosed ? 1 : 0;
        }
        return new CenterBoard(asOf.date(), new RoundRef(round.id(), round.termCode()),
                new Summary(rows.size(), seats, closed), rows, alerts,
                repository.todo(user.id(), round.id()), repository.demand(user.id(), round.id()));
    }

    /** 위험 요인(규칙). 순서: 대상 학과 → 포트폴리오 → 자격증 → 주말 → 문서 검토. */
    static List<Risk> risks(CenterRepository.JobRow job, int alertCount, int narrowPoolBelow) {
        List<Risk> out = new ArrayList<>();
        if (job.eligiblePool() < narrowPoolBelow) {
            out.add(risk(RiskCode.NARROW_POOL, "선호 전공 재학생 " + job.eligiblePool() + "명"));
        }
        if ("REQUIRED".equals(job.portfolio())) {
            out.add(risk(RiskCode.PORTFOLIO_REQUIRED, null));
        }
        if ("REQUIRED".equals(job.certificate())) {
            String text = job.certificateText();
            out.add(risk(RiskCode.CERTIFICATE_REQUIRED, text == null || text.isBlank() ? null : text.strip()));
        }
        List<String> weekend = job.weekdays().stream().filter(d -> d.equals("SAT") || d.equals("SUN"))
                .map(d -> CodeLabels.label("weekday", d)).toList();
        if (!weekend.isEmpty()) {
            out.add(risk(RiskCode.WEEKEND, String.join("·", weekend)));
        }
        if (alertCount > 0) {
            out.add(risk(RiskCode.DOC_ALERT, "검토 알림 " + alertCount + "건"));
        }
        return out;
    }

    private static Risk risk(RiskCode code, String detail) {
        return new Risk(code, CodeLabels.label("risk", code.name()), detail);
    }
}
