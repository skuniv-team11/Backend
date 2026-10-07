package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.recommend.EvidenceText.Segment;

/**
 * 바로 보여 주는 기본 이유 문장(규칙, ADR-0020·0024). 이유 문장(#16) LLM이 실패해도 이 문장이 남는다.
 * 순서: 판정 한 줄(내 학년·학점 등 갖춘 조건, 또는 챙길 것) → 내 학과와 선호 전공 → 관심 분야가 겹친 원문 → 채용연계형 →
 * 추천 안 같은 팀 직무와의 차이. 앞의 두 문장은 {@link FitSentences}가 만든다.
 */
final class ReasonTemplates {

    /** 이보다 긴 원문 조각은 문장에 넣지 않는다(인용 칸에만 보인다). */
    static final int QUOTE_IN_SENTENCE = 40;
    static final String HIRING = "채용연계형 자리예요(실습 뒤 채용으로 이어질 수 있는 유형).";
    private static final int CONTRAST_ITEMS = 2;
    private static final int CONTRAST_ITEM_LENGTH = 30;

    private ReasonTemplates() {
    }

    /**
     * @param lead          판정 한 줄({@link FitSentences#lead})
     * @param major         내 학과와 선호 전공({@link FitSentences#major}). 지원 불가면 null
     * @param interestClose 관심 유사도가 높음(적합도 HIGH 기준과 같음)
     * @param matched       관심 문장과 겹쳐 고른 계획서 조각. 없으면 null
     * @param contrast      같은 팀 다른 추천 직무와의 차이 문장. 없으면 null
     */
    static String build(String lead, String major, boolean interestClose, Segment matched, boolean hiring,
                        String contrast) {
        List<String> out = new ArrayList<>();
        out.add(lead);
        if (major != null) {
            out.add(major);
        }
        if (interestClose) {
            out.add(matched != null && matched.text().length() <= QUOTE_IN_SENTENCE
                    ? "관심 분야가 " + matched.kind().label + " '" + matched.text() + "'" + withOrAnd(matched.text()) + " 겹쳐요."
                    : "관심 분야와 직무 내용이 가까워요.");
        }
        if (hiring) {
            out.add(HIRING);
        }
        if (contrast != null) {
            out.add(contrast);
        }
        return String.join(" ", out);
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
