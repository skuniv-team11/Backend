package kr.ac.skuniv.coopradar.web;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 배포 연결 확인용(E4). 프론트가 이 주소를 불러 CORS까지 통과하는지 본다. */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "service", "coop-radar",
                "time", OffsetDateTime.now().toString());
    }
}
