package kr.ac.skuniv.coopradar.reference;

import java.time.LocalDate;
import java.util.List;

/** 기준 정보 API의 응답 모양(docs/api: departments.json, areas.json, rounds-current.json). 필드 이름이 JSON 이름이다. */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record Department(int id, String name, String college) {
    }

    public record Departments(List<Department> departments) {
    }

    public record Area(String code, String sido, String name) {
    }

    public record Areas(List<Area> areas) {
    }

    /** 자격증 선택지 하나(#26, ADR-0021). code는 프로필 certificates에 넣는 값이다. */
    public record Certificate(String code, String label) {
    }

    public record Certificates(List<Certificate> certificates) {
    }

    /** 현재 모집 회차(#13). {@code replay.defaultAsOf}는 시연 기준일(모집 판정 기준일, ADR-0035)이다. */
    public record CurrentRound(int id, String programName, String termCode, int roundNo, LocalDate recruitStart,
                               LocalDate recruitEnd, Replay replay, List<StageInfo> stages) {
    }

    /** 회차 일정 단계(ADR-0033, round_stage). 순서는 seq. */
    public enum Stage { PICK, APPLY, MATCH, SELECT, CONTRACT, ORIENTATION, PRACTICE, MIDCHECK, CLOSE, DEBRIEF, CREDIT }

    public enum Phase { APPLY, PREPARE, PRACTICE, CLOSE }

    /**
     * 일정 한 단계. 날짜가 없으면 null(학점 인정 등).
     *
     * @param confirmed true 진로취업처 학생 모집안내에 있는 날짜 · false 공지에 날짜가 없어 정한 값(센터 확인 전)
     */
    public record StageInfo(Stage code, Phase phase, LocalDate startsOn, LocalDate endsOn, boolean confirmed,
                            String source) {
    }

    /** @param defaultAsOf 시연 기준일("시연 모드 · 2026-07-23 기준" 배너). 판정·탐색·현황판의 마감을 이 날로 본다 */
    public record Replay(LocalDate defaultAsOf) {
    }
}
