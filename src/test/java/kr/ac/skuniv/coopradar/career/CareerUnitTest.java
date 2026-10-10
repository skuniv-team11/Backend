package kr.ac.skuniv.coopradar.career;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.skuniv.coopradar.career.CareerDrafts.CoveredDraft;
import kr.ac.skuniv.coopradar.career.CareerDrafts.UnitsDraft;
import kr.ac.skuniv.coopradar.career.CareerDtos.Expand;
import kr.ac.skuniv.coopradar.career.CareerDtos.Relation;
import kr.ac.skuniv.coopradar.career.CareerDtos.ReportPath;
import kr.ac.skuniv.coopradar.career.CareerDtos.Unit;
import kr.ac.skuniv.coopradar.career.CareerDtos.UnitRef;
import kr.ac.skuniv.coopradar.career.CareerRepository.Link;
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

    @Test
    void 넓혀_갈_직무는_채운_단위와_이어진_수가_많은_순이고_더_채울_것은_수준이_낮은_것() {
        Unit a1 = new Unit("1111111101_21v1", "A1", 3, null);
        Unit a2 = new Unit("1111111102_21v1", "A2", 4, null);
        Unit b1 = new Unit("2222222201_21v1", "B1", 5, null);
        Unit b2 = new Unit("2222222202_21v1", "B2", 2, null);
        Unit b3 = new Unit("2222222203_21v1", "B3", null, null);
        Unit c1 = new Unit("3333333301_21v1", "C1", 3, null);
        List<Expand> expand = List.of(
                new Expand(1, "22222222", "B", List.of("x", "y", "z"), Relation.OTHER, 3, List.of(), List.of()),
                new Expand(2, "33333333", "C", List.of("x", "y", "z"), Relation.OTHER, 1, List.of(), List.of()));
        List<Link> links = List.of(
                new Link("22222222", a2, b1, "안 채운 단위에서 출발", false),
                new Link("33333333", a1, c1, "채운 단위에서 출발", true));
        Map<String, List<Unit>> units = Map.of("22222222", List.of(b1, b2, b3), "33333333", List.of(c1));
        List<ReportPath> paths = CareerService.paths(expand, links, Set.of(a1.code()), units::get);
        // C가 이어진 1개로 앞, B는 출발 단위(A2)를 안 채워 0개
        assertThat(paths).extracting(ReportPath::code).containsExactly("33333333", "22222222");
        assertThat(paths.get(0).linked()).singleElement().satisfies(l -> {
            assertThat(l.from()).isEqualTo(new UnitRef(a1.code(), "A1", 3));
            assertThat(l.checked()).isTrue();
        });
        assertThat(paths.get(0).more()).isEmpty();
        assertThat(paths.get(1).more()).extracting(UnitRef::name).containsExactly("B2", "B1", "B3");
        // 아무것도 안 채웠으면 순위 순
        assertThat(CareerService.paths(expand, links, Set.of(), units::get)).extracting(ReportPath::rank).containsExactly(1, 2);
    }

    @Test
    void 한_단계_위는_채운_단위에_가장_많은_수준에서_안_채운_것_다음_한_수준_위() {
        List<Unit> units = List.of(
                new Unit("1111111101_21v1", "U1", 3, null), new Unit("1111111102_21v1", "U2", 3, null),
                new Unit("1111111103_21v1", "U3", 5, null), new Unit("1111111104_21v1", "U4", 4, null),
                new Unit("1111111105_21v1", "U5", 3, null), new Unit("1111111106_21v1", "U6", 4, null),
                new Unit("1111111107_21v1", "U7", 4, null), new Unit("1111111108_21v1", "U8", 2, null));
        var next = CareerService.nextLevel(units, Set.of("1111111101_21v1", "1111111103_21v1", "1111111104_21v1"));
        // 수준 3·5·4가 1개씩 → 같으면 낮은 쪽(3). 안 채운 3(U2·U5) → 4(U6)
        assertThat(next.baseLevel()).isEqualTo(3);
        assertThat(next.units()).extracting(UnitRef::name).containsExactly("U2", "U5", "U6");
        // 채운 게 없으면 가장 낮은 수준부터
        assertThat(CareerService.nextLevel(units, Set.of()).baseLevel()).isEqualTo(2);
        assertThat(CareerService.nextLevel(units, Set.of()).units()).extracting(UnitRef::name).containsExactly("U8", "U1", "U2");
    }
}
