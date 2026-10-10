package kr.ac.skuniv.coopradar.career;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.skuniv.coopradar.explore.ExploreDtos.Fallback;
import kr.ac.skuniv.coopradar.job.InstitutionRef;

/** 커리어 응답 모양(docs/api #33~#36: job-career.json, career-report.json, ADR-0032). 필드 이름이 JSON 이름이다. */
public final class CareerDtos {

    private CareerDtos() {
    }

    /** 커리어 리포트가 AI로 능력단위를 골랐는지, AI 없이 목록만인지. */
    public enum Source { AI, NONE }

    /** 넓혀 갈 세분류가 직무 세분류와 어떤 사이인지(코드로 정함). */
    public enum Relation { SAME_SMALL, SAME_MIDDLE, OTHER }

    /** 직업 연결의 출처. KEIS 공식 연계표, CURATED 연계표에 없어 사람이 같은 표의 직업에서 고름. */
    public enum Origin { KEIS, CURATED }

    public record Unit(String code, String name, Integer level, String definition) {
    }

    /** @param path [대분류, 중분류, 소분류] */
    public record Ncs(String code, String name, List<String> path, String note, List<Unit> units) {
    }

    /** 넓혀 갈 세분류. occupations는 그 세분류와 이어진 직업. */
    public record Expand(int rank, String code, String name, List<String> path, Relation relation, int unitCount,
                         List<String> sampleUnits, List<Occupation> occupations) {
    }

    public record Occupation(String code, String name, Origin origin) {
    }

    public record JobCareer(int jobId, Ncs ncs, List<Expand> expand, List<Occupation> occupations) {
    }

    public record ReportNcs(String code, String name, List<String> path, int unitCount) {
    }

    public record Covered(String code, String name, Integer level, String studentQuote, String reason) {
    }

    public record UnitRef(String code, String name, Integer level) {
    }

    /**
     * 채운 능력단위와 이어진 넓혀 갈 세분류의 능력단위.
     * @param from 이어진 채운 단위
     * @param checked false면 AI 초안(사람 확인 전)
     */
    public record Linked(String code, String name, Integer level, UnitRef from, String note, boolean checked) {
    }

    /**
     * 리포트의 넓혀 갈 세분류. 채운 단위와 이어진 수(linkedCount)가 많은 순, 같으면 rank 순.
     * @param more 아직 안 이어진 단위 중 수준이 낮은 것 최대 3개(더 채울 것)
     */
    public record ReportPath(int rank, String code, String name, List<String> path, Relation relation, int unitCount,
                             int linkedCount, List<Linked> linked, List<UnitRef> more, List<Occupation> occupations) {
    }

    /**
     * 같은 세분류 한 단계 위. baseLevel은 채운 단위에 가장 많은 수준(같으면 낮은 쪽, 채운 게 없으면 가장 낮은 수준).
     * @param units 아직 안 채운 단위 중 baseLevel 것, 그다음 baseLevel+1 것 순으로 최대 3개
     */
    public record NextLevel(Integer baseLevel, List<UnitRef> units) {
    }

    public record Input(String practiceText) {
    }

    public record Report(long reportId, OffsetDateTime createdAt, int jobId, String title, String team,
                         InstitutionRef institution, Source source, Fallback fallbackReason, Input input, ReportNcs ncs,
                         List<Covered> covered, List<UnitRef> notCovered, NextLevel nextLevel, List<ReportPath> expand,
                         List<Occupation> occupations) {
    }
}
