package kr.ac.skuniv.coopradar.center;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.center.CenterDtos.CenterBoard;
import kr.ac.skuniv.coopradar.center.CenterDtos.Risk;
import kr.ac.skuniv.coopradar.center.CenterDtos.RiskCode;
import kr.ac.skuniv.coopradar.center.CenterDtos.Row;
import kr.ac.skuniv.coopradar.center.CenterDtos.Summary;
import kr.ac.skuniv.coopradar.job.Alert;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.reference.CodeLabels;
import kr.ac.skuniv.coopradar.reference.RoundService;
import kr.ac.skuniv.coopradar.signal.Signal;
import kr.ac.skuniv.coopradar.signal.SignalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센터 모집 현황판(#24). 지망 점검(#23)과 같은 관심 신호 계산(실제 담은 수는 모든 계정)을 직무 전체 표로 보여 준다.
 * 위험 요인은 규칙이다(AI 없음).
 * 지난 회차 결과(round_result)는 센터 동의 뒤에만 적재하고 원소 모양도 그때 정하므로 지금은 historyAvailable false.
 */
@Service
public class CenterService {

    private final CenterRepository repository;
    private final SignalService signals;
    private final RoundService rounds;
    private final CenterProperties properties;

    public CenterService(CenterRepository repository, SignalService signals, RoundService rounds,
                         CenterProperties properties) {
        this.repository = repository;
        this.signals = signals;
        this.rounds = rounds;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public CenterBoard board(LocalDate requestedAsOf) {
        var round = rounds.current();
        LocalDate asOf = SignalService.resolveAsOf(round, requestedAsOf);
        Map<Integer, Signal> byJob = signals.byJob(round, asOf);
        List<Alert> alerts = repository.alerts(round.id());

        List<Row> rows = new ArrayList<>();
        int seats = 0;
        int interestTotal = 0;
        int liveTotal = 0;
        int zeroSignal = 0;
        int closed = 0;
        for (var job : repository.jobs(round.id())) {
            Signal signal = byJob.get(job.id());
            int alertCount = (int) alerts.stream()
                    .filter(a -> a.institution().id() == job.institution().id()
                            && (a.jobId() == null || a.jobId() == job.id()))
                    .count();
            rows.add(new Row(job.id(), job.institution(), job.title(), job.headcount(), signal, job.eligiblePool(),
                    risks(job, alertCount, properties.narrowPoolBelow()), alertCount, List.of()));
            seats += job.headcount();
            interestTotal += signal.interest();
            liveTotal += signal.liveInterest();
            zeroSignal += signal.interest() == 0 ? 1 : 0;
            closed += signal.status() == Signal.Status.CLOSED ? 1 : 0;
        }
        return new CenterBoard(asOf, true, Signal.Source.REPLAY, new RoundRef(round.id(), round.termCode()),
                new Summary(rows.size(), seats, interestTotal, liveTotal, zeroSignal, closed), false, rows, alerts);
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
