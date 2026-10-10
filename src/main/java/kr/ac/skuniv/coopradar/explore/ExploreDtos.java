package kr.ac.skuniv.coopradar.explore;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.RoundRef;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Blocked;

/** 직무 탐색 응답 모양(docs/api #28~#32: explore-cards.json, explore.json, explore-why.json). 필드 이름이 JSON 이름이다. */
public final class ExploreDtos {

    private ExploreDtos() {
    }

    /** AI가 고른 결과인지, 규칙 추천으로 대신했는지. */
    public enum Source { AI, RULE }

    /** 규칙 추천으로 대신한(또는 '왜 맞나요'를 못 만든) 까닭. */
    public enum Fallback { NO_KEY, LIMITED, AI_ERROR, VERIFY_FAILED, NO_CANDIDATES }

    /** 맞는 정도. 탐색 순위로 정한다(1~2위 STRONG, 3~5위 GOOD). 결과 밖 자리의 '왜 맞나요'는 WEAK. */
    public enum Fit { STRONG, GOOD, WEAK }

    /** 다시 볼 때 판정을 무엇으로 했는지. */
    public enum JudgedWith { SAVED_PROFILE, RUN_PROFILE }

    /** 후보 = ELIGIBLE·NEEDS_CHECK이고 기준일에 마감되지 않은 직무. total = eligible + needsCheck. */
    public record Candidates(int total, int eligible, int needsCheck) {
    }

    public record Card(String id, String text) {
    }

    public record Cards(RoundRef round, Candidates candidates, List<Card> cards) {
    }

    /**
     * @param studentQuote 학생 글에서 그대로 옮긴 구절. 규칙 추천이면 null
     * @param page         jobQuote가 든 운영계획서 쪽. 주차 계획·부서·직무명이면 null
     */
    public record Evidence(String studentQuote, String jobQuote, String documentTitle, Integer page, String reason) {
    }

    public record Point(String text, String studentQuote, String jobQuote) {
    }

    public record JobPhrase(String text, String jobQuote) {
    }

    /** '왜 맞나요'. 구절은 원문과 대조해 통과한 것만. prepare는 없으면 null. */
    public record WhyText(String summary, List<Point> points, List<JobPhrase> tryNew, JobPhrase prepare) {
    }

    public record Item(int rank, int jobId, String title, String team, InstitutionRef institution, Verdict verdict,
                       Fit fit, Evidence evidence, WhyText why) {
    }

    public record Input(List<String> experiences, List<String> cards, String interestText) {
    }

    public record Explore(long runId, OffsetDateTime createdAt, RoundRef round, Source source, Fallback fallbackReason,
                          JudgedWith judgedWith, int hiddenCount, Input input, Candidates candidates, List<Item> items,
                          List<Blocked> blockedBy) {
    }

    public record JobWhy(int jobId, Fit fit, WhyText why, Fallback fallbackReason) {
    }
}
