package kr.ac.skuniv.coopradar.job;

/** 응답에 붙는 모집 회차 요약(계약 스키마 RoundRef). 필드 이름이 JSON 이름이다. */
public record RoundRef(int id, String termCode) {
}
