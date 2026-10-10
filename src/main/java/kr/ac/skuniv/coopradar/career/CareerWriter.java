package kr.ac.skuniv.coopradar.career;

import java.util.Optional;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;

/** 실습 내용 → 능력단위 AI 호출. 실패·시간 초과면 빈 값. 테스트는 가짜로 바꿔 끼운다. */
public interface CareerWriter {

    boolean available();

    String model();

    Optional<UnitsDraft> units(String system, String user);
}
