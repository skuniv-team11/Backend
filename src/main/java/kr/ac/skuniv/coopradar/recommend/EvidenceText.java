package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 운영계획서 직무 원문을 인용할 수 있는 조각으로 나누고, 선배 수기의 팀이 직무 팀과 같은지 본다(ADR-0020). AI 없이 규칙으로.
 * <ul>
 *   <li>조각은 원문 글자 그대로다. 앞의 글머리표·번호·[머리말]·'OA 역량 :' 같은 머리말만 떼고 고치지 않는다
 *       (이유 문장 검증이 원문 대조를 한다). 줄 전체가 [팀명(인원)-설명]이면 괄호 안 설명을 쓴다</li>
 *   <li>150자를 넘는 조각은 문장(마침표 뒤)으로 나누고, 그래도 길면 150자 안쪽의 띄어쓰기에서 끊는다. 끊은 조각도 원문
 *       글자 그대로다(전에는 긴 조각을 버려서 직무 개요가 한 줄로 긴 직무는 교육 목표만 인용됐다, ADR-0022)</li>
 *   <li>근거 쪽이 없는 칸은 인용하지 않는다. 주차 계획은 쪽이 따로 없어서, 서식에서 그 앞(직무 개요)과 뒤(전공 요건)가
 *       같은 쪽일 때만 그 쪽으로 인용한다</li>
 * </ul>
 */
final class EvidenceText {

    static final int MIN_LENGTH = 4;
    static final int MAX_LENGTH = 150;

    enum Kind {
        OVERVIEW("직무 개요"), COMPETENCY("요구 역량"), WEEKLY("주차 계획"), GOAL("교육 목표");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 인용할 수 있는 조각 하나. page는 운영계획서 쪽. */
    record Segment(Kind kind, String text, int page) {
    }

    /**
     * 직무 1개의 원문과 근거 쪽(field_evidence). 쪽이 null이면 그 칸은 근거가 없다.
     *
     * @param planTitle 운영계획서 문서명(예: 소서 운영계획서)
     */
    record JobText(int jobId, int institutionId, String team, String title, String overview, String competencies,
                   String educationGoal, List<String> weeklyPlan, Integer overviewPage, Integer competenciesPage,
                   Integer goalPage, Integer majorPage, String planTitle) {
    }

    private static final Pattern LEAD = Pattern.compile("^(?:[\\s*\\-•▪ㆍ○●◦■□▶►➢✓✔]+|\\d{1,2}[.)]\\s+|\\[[^\\]]{0,40}]\\s*-?\\s*)");
    private static final Pattern INLINE_BULLET = Pattern.compile("\\s*[*•▪]\\s*|\\s+[/\\-]\\s+|\\s+\\d{1,2}\\.\\s+");
    /** 줄 전체가 [팀명(인원)-직무 설명]이면 괄호를 벗기고 '팀명(인원)-'을 뗀다. */
    private static final Pattern WHOLE_BRACKET = Pattern.compile("^\\[(.+)]$");
    private static final Pattern TEAM_HEAD = Pattern.compile("^[^\\-\\]]{0,20}\\(\\d+\\)\\s*-\\s*");
    /** 요구 역량의 'OA 역량 :' 같은 머리말. */
    private static final Pattern LABEL = Pattern.compile("^[^:：]{1,12}[:：]\\s*");
    /** 요건이 아닌 항목(요구 역량 칸에 적힌 '무관'·'제한 없음' 등). */
    private static final Pattern NOT_A_REQUIREMENT = Pattern.compile("^(제한\\s*없음|무관|없음|해당\\s*없음|-)$");
    private static final Pattern TEAM_NOISE = Pattern.compile("\\([^)]*\\)|[\\s\\d\\p{Punct}·&]+");
    /** 문장 끝(마침표·물음표·느낌표 뒤의 띄어쓰기). 긴 조각을 나눌 때 쓴다. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])\\s+");

    private EvidenceText() {
    }

    /** 직무 1개의 인용 조각(직무 개요 → 요구 역량 → 주차 계획 → 교육 목표 순, 같은 글은 한 번만). */
    static List<Segment> segments(JobText j) {
        Set<String> seen = new LinkedHashSet<>();
        List<Segment> out = new ArrayList<>();
        if (j.overviewPage() != null) {
            for (String line : lines(j.overview())) {
                add(out, seen, Kind.OVERVIEW, INLINE_BULLET.split(line), j.overviewPage(), j.overview());
            }
        }
        if (j.competenciesPage() != null) {
            add(out, seen, Kind.COMPETENCY, competencyItems(j.competencies()).toArray(String[]::new),
                    j.competenciesPage(), j.competencies());
        }
        Integer after = j.majorPage() != null ? j.majorPage() : j.competenciesPage();
        if (j.overviewPage() != null && j.overviewPage().equals(after) && j.weeklyPlan() != null) {
            for (String content : j.weeklyPlan()) {
                for (String part : content.split("\\s+\\d{1,2}\\.\\s+")) {
                    add(out, seen, Kind.WEEKLY, slashParts(part).toArray(String[]::new), j.overviewPage(), content);
                }
            }
        }
        if (j.goalPage() != null) {
            add(out, seen, Kind.GOAL, lines(j.educationGoal()).toArray(String[]::new), j.goalPage(), j.educationGoal());
        }
        return out;
    }

    /** 요구 역량을 항목으로(줄·글머리표·' / '·괄호 밖 쉼표로 나눔). 비슷한 직무끼리 차이를 볼 때도 쓴다. */
    static List<String> competencyItems(String competencies) {
        List<String> out = new ArrayList<>();
        for (String line : lines(competencies)) {
            for (String part : INLINE_BULLET.split(line)) {
                for (String item : splitOutsideParens(part, ',')) {
                    String c = LABEL.matcher(clean(item)).replaceFirst("").strip();
                    if (c.length() >= 2 && !NOT_A_REQUIREMENT.matcher(c).matches()) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 수기의 팀이 직무의 팀(또는 직무명)과 같은가. 괄호·숫자·띄어쓰기·끝의 팀/부/실/본부를 떼고 한쪽이 다른 쪽을 품으면 같다고 본다
     * (예: '마케팅솔루션 2팀' ↔ '마케팅솔루션팀', 'OL디자인실' ↔ 'OL디자인/VvOL디자인팀', '강의제작팀' ↔ 직무명 '강의제작').
     */
    static boolean sameTeam(String teamText, String team, String title) {
        String a = normTeam(teamText);
        if (a.length() < 2) {
            return false;
        }
        for (String other : new String[] {team, title}) {
            String b = normTeam(other);
            if (b.length() >= 2 && (a.contains(b) || b.contains(a))) {
                return true;
            }
        }
        return false;
    }

    static String normTeam(String s) {
        String t = TEAM_NOISE.matcher(s == null ? "" : s).replaceAll("");
        for (String suffix : new String[] {"본부", "팀", "부", "실"}) {
            if (t.endsWith(suffix) && t.length() > suffix.length() + 1) {
                return t.substring(0, t.length() - suffix.length());
            }
        }
        return t;
    }

    /** 앞의 글머리표·번호·[머리말]을 떼고 앞뒤 공백을 지운다. 가운데 글자는 그대로다. */
    static String clean(String s) {
        String t = s == null ? "" : s.strip();
        var whole = WHOLE_BRACKET.matcher(t);
        if (whole.matches()) {
            t = TEAM_HEAD.matcher(whole.group(1).strip()).replaceFirst("").strip();
        }
        String before;
        do {
            before = t;
            t = LEAD.matcher(t).replaceFirst("").strip();
        } while (!t.equals(before));
        return t;
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static void add(List<Segment> out, Set<String> seen, Kind kind, String[] parts, int page, String source) {
        String body = squash(source);
        for (String part : parts) {
            for (String c : fit(clean(part))) {
                if (c.length() < MIN_LENGTH || !body.contains(squash(c)) || !seen.add(squash(c))) {
                    continue;
                }
                out.add(new Segment(kind, c, page));
            }
        }
    }

    /** 150자 안쪽 조각으로: 문장(마침표 뒤)으로 나누고, 그래도 길면 150자 안쪽의 마지막 띄어쓰기에서 끊는다. 글자는 고치지 않는다. */
    static List<String> fit(String c) {
        if (c.length() <= MAX_LENGTH) {
            return List.of(c);
        }
        List<String> out = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(c)) {
            String rest = sentence.strip();
            while (rest.length() > MAX_LENGTH) {
                int cut = rest.lastIndexOf(' ', MAX_LENGTH);
                if (cut < MIN_LENGTH) {
                    cut = MAX_LENGTH;
                }
                out.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).strip();
            }
            if (!rest.isEmpty()) {
                out.add(rest);
            }
        }
        return out;
    }

    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return List.of(s.split("\\R"));
    }

    /** '/'로 나누되 4글자 안 되는 조각은 이웃과 다시 붙인다('온/오프라인 데이터'는 한 조각). */
    private static List<String> slashParts(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String piece : s.split("/", -1)) {
            if (!buf.isEmpty()) {
                buf.append('/');
            }
            buf.append(piece);
            if (buf.toString().strip().length() >= MIN_LENGTH && piece.strip().length() >= MIN_LENGTH) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) {
            if (out.isEmpty()) {
                out.add(buf.toString());
            } else {
                out.set(out.size() - 1, out.getLast() + "/" + buf);
            }
        }
        return out;
    }

    private static List<String> splitOutsideParens(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
