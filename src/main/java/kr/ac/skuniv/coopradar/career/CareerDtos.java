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

    public record Expand(int rank, String code, String name, List<String> path, Relation relation, int unitCount,
                         List<String> sampleUnits) {
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

    public record Input(String practiceText) {
    }

    public record Report(long reportId, OffsetDateTime createdAt, int jobId, String title, String team,
                         InstitutionRef institution, Source source, Fallback fallbackReason, Input input, ReportNcs ncs,
                         List<Covered> covered, List<UnitRef> notCovered, List<Expand> expand,
                         List<Occupation> occupations) {
    }
}
