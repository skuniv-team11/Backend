package kr.ac.skuniv.coopradar.eligibility;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import kr.ac.skuniv.coopradar.job.InstitutionRef;
import kr.ac.skuniv.coopradar.job.JobDetail.Closing;
import kr.ac.skuniv.coopradar.job.RoundRef;

/** 판정 응답 모양(docs/api #14, 계약 스키마 Eligibility, eligibility.json). 필드 이름이 JSON 이름이다. */
public final class EligibilityDtos {

    private EligibilityDtos() {
    }

    public enum Verdict { ELIGIBLE, NEEDS_CHECK, INELIGIBLE }

    public enum MajorMatch { MATCH, NOT_LISTED, OPEN }

    public enum Layer { SCHOOL_RULE, INSTITUTION, MAJOR }

    public enum Result { MET, NOT_MET, CHECK, INFO }

    /**
     * 이유 한 줄. alertId는 검토 알림에서 나온 줄에만, citation은 출처가 있는 줄에만 있고 없으면 필드째 빠진다(계약 optional).
     * 출처: 학교 규정 = 학생 모집안내, 학년·학점·선호 전공·포트폴리오 = 참여기관 리스트 칸, 자격증 = 운영계획서(ADR-0021·0023).
     */
    public record ReasonLine(Layer layer, String item, String requirement, String mine, Result result,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Integer alertId,
                             @JsonInclude(JsonInclude.Include.NON_NULL) ReasonCitation citation) {

        static ReasonLine of(Layer layer, String item, String requirement, String mine, Result result) {
            return new ReasonLine(layer, item, requirement, mine, result, null, null);
        }

        static ReasonLine of(Layer layer, String item, String requirement, String mine, Result result,
                             ReasonCitation citation) {
            return new ReasonLine(layer, item, requirement, mine, result, null, citation);
        }
    }

    /**
     * 이유 줄의 출처(계약 스키마 ReasonCitation — Citation과 같은 모양). 원문 PDF 링크는 주지 않는다.
     *
     * @param sourceType OPERATION_PLAN 운영계획서 · INSTITUTION_LIST 참여기관 리스트 · SCHOOL_NOTICE 학생 모집안내
     * @param page       쪽이 없는 문서(참여기관 리스트·모집안내 웹 공지)면 null
     */
    public record ReasonCitation(String sourceType, String documentTitle, Integer page, String quote) {
    }

    /**
     * @param closing    모집마감(직무 상세와 같은 값, 기준일과 상관없이 고정). 목록은 closesOn ≤ 기준일이면 '마감' 꼬리표
     * @param alertCount 그 직무에 걸린 검토 알림 수(기관 단위 제외). 목록의 '문서 검토' 꼬리표
     */
    public record EligibilityJob(int jobId, String title, String team, InstitutionRef institution, Verdict verdict,
                                 MajorMatch majorMatch, Closing closing, int alertCount, List<ReasonLine> reasons) {
    }

    public record Summary(int total, int eligible, int needsCheck, int ineligible) {
    }

    public record Eligibility(RoundRef round, Summary summary, List<EligibilityJob> jobs) {
    }
}
