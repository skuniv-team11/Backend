package kr.ac.skuniv.coopradar.reference;

import java.util.List;
import java.util.Map;
import kr.ac.skuniv.coopradar.auth.PublicApi;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Areas;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Certificates;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Departments;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/api #2 코드값 · #11 학과 · #12 사는 곳 · #13 현재 회차 · #26 자격증. 로그인 전 화면(S1)에서도 쓰므로 전부 공개. */
@RestController
@RequestMapping("/api")
@PublicApi
public class ReferenceController {

    private final ReferenceRepository repository;
    private final RoundService rounds;

    public ReferenceController(ReferenceRepository repository, RoundService rounds) {
        this.repository = repository;
        this.rounds = rounds;
    }

    @GetMapping("/codes")
    public Map<String, Map<String, String>> codes() {
        return CodeLabels.ALL;
    }

    @GetMapping("/departments")
    public Departments departments() {
        return new Departments(repository.departments());
    }

    @GetMapping("/areas")
    public Areas areas() {
        return new Areas(repository.areas());
    }

    /** 현재 회차 직무가 요구·우대하는 자격증(ADR-0021). 회차 시드 전이면 빈 목록. */
    @GetMapping("/certificates")
    public Certificates certificates() {
        return new Certificates(repository.latestRound().map(r -> repository.certificates(r.id())).orElse(List.of()));
    }

    @GetMapping("/rounds/current")
    public CurrentRound currentRound() {
        return rounds.current();
    }
}
