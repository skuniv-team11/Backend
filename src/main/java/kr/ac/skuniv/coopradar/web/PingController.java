package kr.ac.skuniv.coopradar.web;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import kr.ac.skuniv.coopradar.auth.PublicApi;
import kr.ac.skuniv.coopradar.common.Times;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 배포 연결 확인용(E4). 프론트가 이 주소를 불러 CORS까지 통과하는지 본다. 시각은 한국 시간, 초 단위. */
@RestController
@PublicApi
public class PingController {

    private final Clock clock;

    public PingController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "service", "coop-radar",
                "time", Times.kst(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
    }
}
