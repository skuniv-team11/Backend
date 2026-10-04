package kr.ac.skuniv.coopradar.job;

/**
 * 응답에 붙는 기관 요약(계약 스키마 InstitutionRef). 필드 이름이 JSON 이름이다.
 * {@code logoPath}는 API 서버 기준 경로(예: /logos/3.png), 로고가 없으면 null(ADR-0019).
 */
public record InstitutionRef(int id, String name, String logoPath) {

    public InstitutionRef(int id, String name) {
        this(id, name, InstitutionLogos.pathFor(id));
    }
}
