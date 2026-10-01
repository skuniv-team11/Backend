package kr.ac.skuniv.coopradar.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 현재 시각은 이 Clock으로만 읽는다(테스트에서 바꿀 수 있게). 체험 계정 정리 같은 예약 작업도 여기서 켠다. */
@Configuration
@EnableScheduling
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
