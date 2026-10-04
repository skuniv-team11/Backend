package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.JobText;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Kind;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Segment;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.Citation;
import kr.ac.skuniv.coopradar.recommend.RecommendDtos.SourceType;

/**
 * 추천 카드의 근거 인용 2개를 고른다(ADR-0020). 외부 호출 없이 키워드 유사도(ADR-0018과 같은 글자 2~3-gram TF-IDF)로.
 * <ul>
 *   <li>운영계획서: 직무 원문 조각(직무 개요·요구 역량·주차 계획·교육 목표) 중 관심 문장과 가장 많이 겹치는 것.
 *       겹치는 게 {@link #MIN_MATCH} 미만이거나 관심 문장이 없으면 직무 개요 첫 조각(없으면 교육 목표)</li>
 *   <li>선배 수기: <b>같은 팀</b> 수기만. 그중 관심 문장과 가장 많이 겹치는 실습 내용 한 줄, 없으면 가장 최근 수기의 첫 줄.
 *       같은 팀 수기가 없으면 붙이지 않는다(다른 팀의 일을 이 직무의 근거로 보이지 않게)</li>
 * </ul>
 * 고른 계획서 조각은 기본 이유 문장도 그대로 인용한다 — 문장과 인용이 같은 곳을 가리키게.
 */
final class EvidencePicker {

    /** 이보다 낮게 겹치면 우연히 같은 글자 조각('브랜드', '데이' 등)으로 보고 쓰지 않는다. 2026-2 실측으로 정함(ADR-0020). */
    static final double MIN_MATCH = 0.15;
    /**
     * 수기 실습 내용 한 줄은 더 짧고, 후보가 이미 같은 팀 수기로 좁혀져 있어서 기준을 낮춘다. 수기 인용은 문장에서
     * '관심과 겹친다'고 말하지 않는다(그 말은 계획서 조각에만 한다).
     */
    static final double MIN_MATCH_TESTIMONIAL = 0.10;

    /** 선배 수기 1건(최근 학기 먼저). */
    record Testimonial(int institutionId, String documentTitle, String termCode, String teamText, int page,
                       List<String> activities) {
    }

    /**
     * @param citations 수기 → 운영계획서 순, 0~2개
     * @param matched   관심 문장과 겹쳐서 고른 계획서 조각. 기본값으로 고른 것이면 null
     */
    record Picked(List<Citation> citations, Segment matched) {
    }

    private final String interest;
    private final KeywordSimilarity model;
    private final Map<String, Double> query;
    private final Map<Integer, List<Segment>> segments = new HashMap<>();

    /** 회차 직무 전부의 조각·수기 실습 내용·관심 문장을 문서로 보고 IDF를 센다. */
    EvidencePicker(Collection<JobText> jobs, Collection<Testimonial> testimonials, String interestText) {
        this.interest = interestText == null || interestText.isBlank() ? null : interestText.strip();
        List<String> corpus = new ArrayList<>();
        for (JobText j : jobs) {
            List<Segment> s = EvidenceText.segments(j);
            segments.put(j.jobId(), s);
            s.forEach(x -> corpus.add(x.text()));
        }
        testimonials.forEach(t -> corpus.addAll(t.activities()));
        if (interest != null) {
            corpus.add(interest);
        }
        this.model = interest == null ? null : new KeywordSimilarity(corpus);
        this.query = interest == null ? Map.of() : model.vector(interest);
    }

    /** @param institutionTestimonials 그 기관의 수기(최근 학기 먼저) */
    Picked pick(JobText job, List<Testimonial> institutionTestimonials) {
        List<Citation> out = new ArrayList<>();
        testimonial(job, institutionTestimonials).ifPresent(out::add);
        List<Segment> segs = segments.getOrDefault(job.jobId(), List.of());
        Segment matched = best(segs);
        Segment plan = matched != null ? matched : fallback(segs);
        if (plan != null && job.planTitle() != null) {
            out.add(new Citation(SourceType.OPERATION_PLAN, job.planTitle(), plan.page(), plan.text()));
        }
        return new Picked(List.copyOf(out), matched);
    }

    private Optional<Citation> testimonial(JobText job, List<Testimonial> all) {
        List<Testimonial> same = all.stream()
                .filter(t -> EvidenceText.sameTeam(t.teamText(), job.team(), job.title()))
                .filter(t -> !t.activities().isEmpty())
                .toList();
        if (same.isEmpty()) {
            return Optional.empty();
        }
        Testimonial bestT = same.getFirst();
        String bestLine = bestT.activities().getFirst();
        double bestSim = 0;
        if (interest != null) {
            for (Testimonial t : same) {
                for (String line : t.activities()) {
                    double sim = similarity(line);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestT = t;
                        bestLine = line;
                    }
                }
            }
            if (bestSim < MIN_MATCH_TESTIMONIAL) {
                bestT = same.getFirst();
                bestLine = bestT.activities().getFirst();
            }
        }
        return Optional.of(new Citation(SourceType.TESTIMONIAL, bestT.documentTitle(), bestT.page(), bestLine));
    }

    private Segment best(List<Segment> segs) {
        if (interest == null) {
            return null;
        }
        return segs.stream()
                .map(s -> Map.entry(s, similarity(s.text())))
                .filter(e -> e.getValue() >= MIN_MATCH)
                .max(Comparator.comparingDouble(Map.Entry<Segment, Double>::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static Segment fallback(List<Segment> segs) {
        for (Kind k : new Kind[] {Kind.OVERVIEW, Kind.GOAL}) {
            for (Segment s : segs) {
                if (s.kind() == k) {
                    return s;
                }
            }
        }
        return null;
    }

    double similarity(String text) {
        return interest == null ? 0 : KeywordSimilarity.cosine(query, model.vector(text));
    }
}
