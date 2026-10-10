package kr.ac.skuniv.coopradar.career;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.skuniv.coopradar.career.CareerDrafts.CoveredDraft;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;
import kr.ac.skuniv.coopradar.career.CareerDtos.Unit;
import org.junit.jupiter.api.Test;

/** 커리어 리포트 대조 규칙(DB·AI 없이). */
class CareerUnitTest {

    static final List<Unit> UNITS = List.of(
            new Unit("0201030102_21v5", "신상품 기획", 5, "정의"),
            new Unit("0201030109_21v4", "마케팅 시장 환경 분석", 3, "정의"),
            new Unit("0201030115_16v3", "마케팅 성과파악", 3, "정의"));

    @Test
    void 단위_코드는_같거나_개정_표기만_다르면_찾는다() {
        assertThat(CareerService.resolve("0201030102_21v5", UNITS)).isEqualTo("0201030102_21v5");
        // 2026-10-10 실제 호출: 판 번호만 틀림
        assertThat(CareerService.resolve("0201030102_21v4", UNITS)).isEqualTo("0201030102_21v5");
        assertThat(CareerService.resolve("0201020205_24v4", UNITS)).isNull();
        assertThat(CareerService.resolve("신상품 기획", UNITS)).isNull();
    }

    @Test
    void 구절은_한_문장_안에_있어야_한다() {
        String text = "경쟁 브랜드 10곳의 신제품을 조사해 정리했습니다. 매주 SNS 반응을 집계했습니다.\n마지막 주에 결과를 발표했습니다.";
        var ok = CareerService.verify(new UnitsDraft(List.of(
                new CoveredDraft("0201030109_21v4", "경쟁 브랜드 10곳의 신제품을 조사해", "경쟁 제품을 조사한 일이 시장 환경 분석이에요."),
                new CoveredDraft("0201030115_16v3", "정리했습니다. 매주 SNS 반응을", "두 문장에 걸친 구절이라 빠져요."),
                new CoveredDraft("0201030102_21v5", "매주 SNS 반응을 집계했습니다", "해라체라 빠진다."))), UNITS, text);
        assertThat(ok).containsOnlyKeys("0201030109_21v4");
        assertThat(CareerService.pieces(text)).hasSize(3);
    }
}
