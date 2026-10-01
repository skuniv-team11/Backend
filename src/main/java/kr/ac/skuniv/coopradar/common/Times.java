package kr.ac.skuniv.coopradar.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** 응답의 시각은 한국 시간(+09:00), 초 단위로 맞춘다(계약 예시 형식). DB에는 UTC로 넣는다. */
public final class Times {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Times() {
    }

    public static OffsetDateTime kst(Instant instant) {
        return instant == null ? null : instant.atZone(KST).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
    }

    public static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : kst(time.toInstant());
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
