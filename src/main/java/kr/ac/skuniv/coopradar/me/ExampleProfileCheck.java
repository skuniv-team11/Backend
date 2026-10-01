package kr.ac.skuniv.coopradar.me;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 때 예시 프로필에 필요한 시드가 있는지 본다. 없어도 서버는 뜨고 [예시 프로필로 시작]은 프로필 없이 계정만 만든다.
 * 시드가 들어오면 다시 띄울 필요 없이 다음 요청부터 예시 프로필이 붙는다.
 */
@Component
class ExampleProfileCheck {

    private static final Logger log = LoggerFactory.getLogger(ExampleProfileCheck.class);

    private final ProfileRepository profiles;
    private final ExampleProfileProperties example;

    ExampleProfileCheck(ProfileRepository profiles, ExampleProfileProperties example) {
        this.profiles = profiles;
        this.example = example;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        if (profiles.findDepartmentId(example.departmentName()).isEmpty()) {
            log.warn("department 시드에 예시 학과 '{}'가 없어 [예시 프로필로 시작]은 프로필 없이 계정만 만듭니다. 시드를 넣으면 풀립니다",
                    example.departmentName());
        }
        if (example.homeAreaCode() != null && !profiles.areaExists(example.homeAreaCode())) {
            log.info("area 시드에 사는 곳 {}가 없어 예시 프로필의 사는 곳은 비웁니다(좌표 대기, ADR-0007)", example.homeAreaCode());
        }
    }
}
