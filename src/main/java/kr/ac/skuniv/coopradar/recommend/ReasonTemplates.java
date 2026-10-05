package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.MajorMatch;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Segment;

/**
 * 바로 보여 주는 기본 이유 문장(규칙, ADR-0020). 이유 문장(#16) LLM이 실패해도 이 문장이 남는다.
 * <ol>
 *   <li>본문: '관심 분야 → 선호 전공 → 채용연계형' 순으로 해당하는 이유를 두 개까지 잇는다. 관심 분야가 겹친 곳과
 *       선호 전공 표기는 원문 그대로 따옴표로 짚는다(인용 근거와 같은 글)</li>
 *   <li>선호 전공에 소속 학과가 없으면 그 사실을 한 문장 덧붙인다(선호 전공은 참고 사항 — 판정에 넣지 않는다)</li>
 *   <li>추천 안에 같은 기관·같은 팀의 다른 직무가 있으면 요건이 다른 점을 한 문장 덧붙인다(예: 국내 ↔ 해외 마케팅)</li>
 * </ol>
 */
final class ReasonTemplates {

    /** 이보다 긴 원문 조각은 문장에 넣지 않는다(인용 칸에만 보인다). */
    static final int QUOTE_IN_SENTENCE = 40;
    static final String MAJOR_NOT_LISTED = "선호 전공에 소속 학과는 없어요(선호 전공은 참고 사항이에요).";
    private static final int CONTRAST_ITEMS = 2;
    private static final int CONTRAST_ITEM_LENGTH = 30;

    private ReasonTemplates() {
    }

    /**
     * @param majorLabel    소속 학과가 들어 있는 선호 전공 표기(예: 미용예술대학). MATCH가 아니면 null
     * @param interestClose 관심 유사도가 높음(적합도 HIGH 기준과 같음)
     * @param matched       관심 문장과 겹쳐 고른 계획서 조각. 없으면 null
     * @param contrast      같은 팀 다른 추천 직무와의 차이 문장. 없으면 null
     */
    static String build(MajorMatch major, String majorLabel, boolean interestClose, Segment matched, boolean hiring,
                        boolean eligible, String contrast) {
        List<String[]> clauses = new ArrayList<>(); // {이어지는 꼴, 끝맺는 꼴}
        if (interestClose) {
            if (matched != null && matched.text().length() <= QUOTE_IN_SENTENCE) {
                String head = "관심 분야가 " + matched.kind().label + " '" + matched.text() + "'" + withOrAnd(matched.text());
                clauses.add(new String[] {head + " 겹치고", head + " 겹쳐요"});
            } else {
                clauses.add(new String[] {"관심 분야와 직무 내용이 가깝고", "관심 분야와 직무 내용이 가까워요"});
            }
        }
        if (major == MajorMatch.MATCH) {
            String head = majorLabel == null ? "선호 전공에" : "선호 전공 '" + majorLabel + "'에";
            clauses.add(new String[] {head + " 소속 학과가 들어 있고", head + " 소속 학과가 들어 있어요"});
        } else if (major == MajorMatch.OPEN) {
            clauses.add(new String[] {"전공 무관 자리이고", "전공 무관 자리예요"});
        }
        if (hiring) {
            clauses.add(new String[] {"채용연계형이고", "채용연계형이에요"});
        }
        String text;
        if (clauses.isEmpty()) {
            text = eligible ? "지원 조건을 모두 통과한 자리예요." : "확인할 조건만 챙기면 지원할 수 있는 자리예요.";
        } else if (clauses.size() == 1) {
            text = clauses.getFirst()[1] + ".";
        } else {
            text = clauses.get(0)[0] + ", " + clauses.get(1)[1] + ".";
        }
        if (major == MajorMatch.NOT_LISTED) {
            text += " " + MAJOR_NOT_LISTED;
        }
        if (contrast != null) {
            text += " " + contrast;
        }
        return text;
    }

    /**
     * 선호 전공에 소속 학과가 있는지 한 문장. 이유 문장(#16) LLM 문장 뒤에 늘 붙인다 — LLM은 선호 전공을 말하지 않고
     * 이 사실은 규칙이 맡는다(ADR-0020 10/5 보완). 기본 문장의 선호 전공 문구와 같은 글이다.
     *
     * @param majorLabel 소속 학과가 들어 있는 선호 전공 표기(예: 미용예술대학). MATCH가 아니면 무시
     */
    static String majorSentence(MajorMatch major, String majorLabel) {
        return switch (major) {
            case MATCH -> (majorLabel == null ? "선호 전공에" : "선호 전공 '" + majorLabel + "'에") + " 소속 학과가 들어 있어요.";
            case OPEN -> "전공 무관 자리예요.";
            case NOT_LISTED -> MAJOR_NOT_LISTED;
        };
    }

    /**
     * '확인 필요'로 만든 기관 조건(못 미친 조건·직접 확인할 서류·문서 검토)을 판정 이유 글 그대로 한 문장으로.
     * 없으면 null. 예: "확인해야 할 조건이 있어요(학년 '4학년' · 포트폴리오 '필수')."
     * LLM은 이 조건을 쓰지 않고(바꿔 말하다 '4학년'을 '4학년 이상'으로 쓰는 일이 있었다) 이 문장이 맡는다.
     */
    static String checkSentence(List<ReasonLine> reasons) {
        List<String> items = reasons.stream()
                .filter(r -> r.layer() == Layer.INSTITUTION && (r.result() == Result.NOT_MET || r.result() == Result.CHECK))
                .map(r -> r.item() + " '" + r.requirement() + "'")
                .distinct()
                .toList();
        return items.isEmpty() ? null : "확인해야 할 조건이 있어요(" + String.join(" · ", items) + ").";
    }

    /**
     * 같은 기관·같은 팀의 다른 추천 직무와 요건이 다른 점(요구 역량 항목·학년·학점·포트폴리오·자격증). 다른 게 없거나
     * 세 개 이상이면(하는 일이 다른 직무) null.
     * 예: 국내 마케팅 쪽 → "같은 팀의 해외 마케팅과 달리 '영어 가능자' 요건은 없어요."
     */
    static String contrast(JobRequirement me, String myCompetencies, JobRequirement other, String otherCompetencies) {
        Set<String> mine = requirements(me, myCompetencies);
        Set<String> theirs = requirements(other, otherCompetencies);
        List<String> onlyMine = only(mine, theirs);
        List<String> onlyTheirs = only(theirs, mine);
        // 거의 같은 직무(요건 차이가 두 개 이하)일 때만 말한다. 하는 일이 다른 직무끼리 요건을 늘어놓으면 오히려 헷갈린다.
        // 긴 항목이 끼어도 문장이 길어지므로 말하지 않는다
        boolean tooLong = Stream.concat(onlyMine.stream(), onlyTheirs.stream())
                .anyMatch(x -> x.length() > CONTRAST_ITEM_LENGTH);
        if (onlyMine.isEmpty() && onlyTheirs.isEmpty() || onlyMine.size() + onlyTheirs.size() > CONTRAST_ITEMS || tooLong) {
            return null;
        }
        String head = "같은 팀의 " + other.title() + withOrAnd(other.title()) + " 달리 ";
        if (onlyTheirs.isEmpty()) {
            return head + quoted(onlyMine) + " 요건이 있어요.";
        }
        if (onlyMine.isEmpty()) {
            return head + quoted(onlyTheirs) + " 요건은 없어요.";
        }
        return head + quoted(onlyMine) + " 요건이 있고 " + quoted(onlyTheirs) + " 요건은 없어요.";
    }

    private static Set<String> requirements(JobRequirement r, String competencies) {
        Set<String> out = new LinkedHashSet<>(EvidenceText.competencyItems(competencies));
        switch (r.gradeRule()) {
            case "Y4" -> out.add("4학년");
            case "GRADUATING" -> out.add("졸업예정자");
            default -> { }
        }
        if (r.gpaMin() != null) {
            out.add("학점 " + r.gpaMin().stripTrailingZeros().toPlainString().replaceAll("^(\\d)$", "$1.0") + " 이상");
        }
        if ("REQUIRED".equals(r.portfolio())) {
            out.add("포트폴리오 필수");
        }
        if ("REQUIRED".equals(r.certificate())) {
            out.add("자격증 필수");
        }
        return out;
    }

    private static List<String> only(Set<String> a, Set<String> b) {
        Set<String> squashedB = new LinkedHashSet<>();
        b.forEach(x -> squashedB.add(EvidenceText.squash(x)));
        return a.stream().filter(x -> !squashedB.contains(EvidenceText.squash(x))).toList();
    }

    private static String quoted(List<String> items) {
        return "'" + String.join("' · '", items) + "'";
    }

    /** 앞말 끝 글자에 받침이 있으면 '과', 없으면 '와'. 한글이 아니면 '와'. */
    static String withOrAnd(String word) {
        String w = word.strip();
        char last = w.isEmpty() ? ' ' : w.charAt(w.length() - 1);
        if (last >= '가' && last <= '힣') {
            return (last - '가') % 28 == 0 ? "와" : "과";
        }
        return "와";
    }
}
