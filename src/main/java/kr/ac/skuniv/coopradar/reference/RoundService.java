package kr.ac.skuniv.coopradar.reference;

import java.time.LocalDate;
import kr.ac.skuniv.coopradar.common.ApiException;
import kr.ac.skuniv.coopradar.common.ErrorCode;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.CurrentRound;
import kr.ac.skuniv.coopradar.reference.ReferenceDtos.Replay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 현재 모집 회차와 리플레이 날짜 범위(#13). 지망 점검·현황판도 asOf 기본값과 범위를 여기서 가져간다. */
@Service
public class RoundService {

    private static final Logger log = LoggerFactory.getLogger(RoundService.class);

    private final ReferenceRepository repository;
    private final ReplayProperties replay;

    public RoundService(ReferenceRepository repository, ReplayProperties replay) {
        this.repository = repository;
        this.replay = replay;
    }

    /** 회차 시드가 없으면 500 INTERNAL(계약상 이 API만의 오류는 없다). 시드 전 상태라 서버 설정 문제로 본다. */
    public CurrentRound current() {
        var row = repository.latestRound().orElseThrow(() -> {
            log.warn("recruit_round 시드(모집 시작·종료일 포함)가 없어 현재 회차를 정할 수 없습니다");
            return new ApiException(ErrorCode.INTERNAL, "모집 회차 정보가 아직 없어요");
        });
        LocalDate defaultAsOf = clamp(replay.defaultAsOf(), row.recruitStart(), row.recruitEnd());
        return new CurrentRound(row.id(), row.programName(), row.termCode(), row.roundNo(), row.recruitStart(),
                row.recruitEnd(), new Replay(defaultAsOf, row.recruitStart(), row.recruitEnd(), true));
    }

    /** 설정한 기준일이 없거나 모집기간 밖이면 가까운 끝 날짜(없으면 시작일). */
    static LocalDate clamp(LocalDate date, LocalDate min, LocalDate max) {
        if (date == null || date.isBefore(min)) {
            return min;
        }
        return date.isAfter(max) ? max : date;
    }
}
