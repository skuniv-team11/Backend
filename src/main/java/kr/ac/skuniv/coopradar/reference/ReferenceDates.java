package kr.ac.skuniv.coopradar.reference;

import java.time.Clock;
import java.time.LocalDate;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import org.springframework.stereotype.Component;

/**
 * 기준일 두 가지와 마감 판정을 한 곳에서 정한다(ADR-0035).
 * <ul>
 *   <li>모집 판정 기준일({@link #recruit}): 판정·추천·탐색·지망 점검의 '마감'·후보. 리플레이 중에는 {@code replay.defaultAsOf}
 *       (회차 모집기간 안으로 맞춘 값), 운영 때 오늘로 바꿀 자리는 여기 하나다</li>
 *   <li>진행 기준일({@link #progress}): 지원서·내 현장실습. 요청한 날 → 체험 학생의 기준일 → 오늘(한국 시간)</li>
 *   <li>마감({@link AsOf#closed}): closesOn ≤ 기준일이거나 기준일이 회차 모집 종료일 뒤</li>
 * </ul>
 */
@Component
public class ReferenceDates {

    private final Clock clock;

    public ReferenceDates(Clock clock) {
        this.clock = clock;
    }

    /**
     * 기준일 하나와 그 회차의 모집 종료일. 마감 판정은 이것으로만 한다.
     *
     * @param recruitEnd 모르면 null(종료일 규칙을 보지 않는다)
     */
    public record AsOf(LocalDate date, LocalDate recruitEnd) {

        /** 이 날 지원할 수 없는 자리인지: closesOn ≤ 기준일(그날부터 지원 불가)이거나 모집이 끝났다. */
        public boolean closed(LocalDate closesOn) {
            if (date == null) {
                return false;
            }
            return (closesOn != null && !closesOn.isAfter(date)) || (recruitEnd != null && date.isAfter(recruitEnd));
        }
    }

    /** 모집 판정 기준일. */
    public static AsOf recruit(CurrentRound round) {
        return new AsOf(round.replay().defaultAsOf(), round.recruitEnd());
    }

    /** 그 회차의 어떤 날. */
    public static AsOf on(LocalDate date, CurrentRound round) {
        return new AsOf(date, round.recruitEnd());
    }

    /** 진행 기준일: 요청한 날 → 체험 학생의 기준일 → 오늘(한국 시간). */
    public LocalDate progress(LocalDate requested, LocalDate demoToday) {
        if (requested != null) {
            return requested;
        }
        return demoToday != null ? demoToday : today();
    }

    /** 오늘(한국 시간). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(Times.KST));
    }
}
