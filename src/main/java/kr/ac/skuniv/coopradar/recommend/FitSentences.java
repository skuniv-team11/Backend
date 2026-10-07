package kr.ac.skuniv.coopradar.recommend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.EligibilityJob;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Layer;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.ReasonLine;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Result;
import kr.ac.skuniv.coopradar.eligibility.EligibilityDtos.Verdict;
import kr.ac.skuniv.coopradar.eligibility.EligibilityRules;
import kr.ac.skuniv.coopradar.eligibility.EligibilityService.Judged;
import kr.ac.skuniv.coopradar.eligibility.JobRequirement;
import kr.ac.skuniv.coopradar.recommend.FitScorer.MajorTier;

/**
 * '왜 나에게 맞는지(또는 왜 지금은 안 되는지)'를 학생의 값으로 말하는 규칙 문장(ADR-0024). 판정 이유 줄(요건 ↔ 내 값)을
 * 그대로 문장으로 옮긴다 — LLM은 이 말을 하지 않고 하는 일만 쓴다.
 * <ul>
 *   <li>{@link #lead}: 판정 한 줄. 지원 가능이면 갖춘 조건을 내 값과 함께, 확인 필요면 챙길 것(포트폴리오·엇갈린 문서),
 *       지원 불가면 못 맞춘 조건을 하나씩</li>
 *   <li>{@link #major}: 내 학과가 회사가 선호하는 전공에 드는지 · 가까운지 · 먼지(ADR-0026·0028). 가깝거나 먼 전공은 지난
 *       매칭 집계({@link MatchingHistory})를 근거로 붙인다</li>
 * </ul>
 */
public final class FitSentences {

    static final String CANNOT = "지금은 지원할 수 없어요.";
    static final String OPEN = "전공을 따지지 않는 자리예요.";

    private FitSentences() {
    }

    /**
     * 판정 한 줄(지원 불가면 못 맞춘 조건까지). 문장은 판정 이유 줄의 요건·내 값을 그대로 쓴다.
     *
     * @param certificateLabels 자격증 코드 → 이름(코드표). 없으면 직무의 자격증 원문을 쓴다
     */
    public static String lead(Judged judged, Map<String, String> certificateLabels) {
        EligibilityJob job = judged.result();
        String certificate = certificateName(judged.requirement(), certificateLabels);
        return switch (job.verdict()) {
            case INELIGIBLE -> ineligible(job.reasons(), certificate);
            case NEEDS_CHECK -> toPrepare(job.reasons(), certificate);
            case ELIGIBLE -> eligible(job.reasons(), certificate);
        };
    }

    /**
     * 내 학과와 회사가 선호하는 전공(ADR-0026·0028). 지원 불가 직무에는 쓰지 않는다(null).
     * <ul>
     *   <li>학과 지명: "…선호하는 전공이에요" / "…전공('무대패션디자인전공')에 들어가요"</li>
     *   <li>계열: "…선호하는 전공 범위('미용예술대학')에 들어가요"</li>
     *   <li>가까운 전공: "…선호하는 전공('소프트웨어학과')과 가까운 전공이에요." + 지난 매칭 근거</li>
     *   <li>먼 전공: "…선호하는 전공(광고홍보콘텐츠학과·경영학부 등)과는 거리가 있는 전공이에요." + 지난 매칭 근거</li>
     * </ul>
     *
     * @param majorLabel 학과 지명·계열이면 내 학과가 든 가장 좁은 선호 전공 표기(학과 이름과 같으면 다시 말하지 않는다),
     *                   가까운 전공이면 가까운 학과가 든 가장 좁은 표기, 먼 전공이면 선호 전공 표기 요약(예: 'A·B 등')
     */
    public static String major(Verdict verdict, MajorTier tier, String department, String majorLabel) {
        if (verdict == Verdict.INELIGIBLE) {
            return null;
        }
        String me = department == null || department.isBlank() ? "내 학과" : department.strip();
        String label = majorLabel == null || majorLabel.isBlank() ? null : majorLabel.strip();
        return switch (tier) {
            case OPEN -> OPEN;
            case DIRECT -> label == null || label.equals(me) ? Josa.eunNeun(me) + " 회사가 선호하는 전공이에요."
                    : Josa.eunNeun(me) + " 회사가 선호하는 전공('" + label + "')에 들어가요.";
            case GROUP -> Josa.eunNeun(me) + " 회사가 선호하는 전공 범위" + (label == null ? "" : "('" + label + "')")
                    + "에 들어가요.";
            case NEAR -> {
                String preferred = "회사가 선호하는 전공" + (label == null ? "" : "('" + label + "')");
                yield Josa.eunNeun(me) + " " + preferred + Josa.gwaWa(preferred) + " 가까운 전공이에요. "
                        + MatchingHistory.nearEvidence();
            }
            case NONE -> {
                String preferred = "회사가 선호하는 전공" + (label == null ? "" : "(" + label + ")");
                yield Josa.eunNeun(me) + " " + preferred + Josa.gwaWa(preferred) + "는 거리가 있는 전공이에요. "
                        + MatchingHistory.farEvidence();
            }
        };
    }

    /** 먼 전공 문장의 선호 전공 요약: 표기 둘까지 '·'로 잇고 더 있으면 ' 등'. 표기가 없으면 null. */
    public static String labelSummary(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        String head = String.join("·", labels.subList(0, Math.min(2, labels.size())));
        return labels.size() > 2 ? head + " 등" : head;
    }

    /** 선호 전공 원문: 판정의 선호 전공 줄의 '필요 조건'(전공 무관이면 '전공 무관'). */
    public static String majorText(EligibilityJob job) {
        return job.reasons().stream().filter(r -> r.layer() == Layer.MAJOR).map(ReasonLine::requirement).findFirst()
                .orElse(null);
    }

    /** 학과 이름: 판정의 선호 전공 줄의 '내 조건'. */
    public static String department(EligibilityJob job) {
        return job.reasons().stream().filter(r -> r.layer() == Layer.MAJOR).map(ReasonLine::mine).findFirst().orElse(null);
    }

    // ───────── 판정별 ─────────

    private static String ineligible(List<ReasonLine> reasons, String certificate) {
        List<String> out = new ArrayList<>();
        out.add(CANNOT);
        for (ReasonLine r : reasons) {
            if (!EligibilityRules.blocks(r)) {
                continue;
            }
            out.add(switch (r.item()) {
                case "이수 학기" -> "현장실습은 " + r.requirement().replace(" 이상", "") + " 이상 마쳐야 지원할 수 있는데 지금 "
                        + Josa.eulReul(r.mine()) + " 마쳤어요.";
                case "졸업예정자 계절제" -> "졸업 예정자는 방학 과정에 참여할 수 없어요.";
                case "학년" -> "졸업예정자".equals(r.requirement()) ? "졸업 예정자만 지원할 수 있어요."
                        : r.requirement() + "만 지원할 수 있는데 지금 " + Josa.ieyo(r.mine()) + ".";
                case "학점" -> "학점 " + Josa.ieoya(r.requirement()) + " 하는데 지금 " + Josa.ieyo(r.mine()) + ".";
                case EligibilityRules.CERTIFICATE_ITEM -> Josa.iGa(certificate) + " 있어야 하는데 프로필에 없어요.";
                default -> r.item() + " 조건(" + r.requirement() + ")을 못 맞췄어요.";
            });
        }
        return String.join(" ", out);
    }

    private static String toPrepare(List<ReasonLine> reasons, String certificate) {
        List<ReasonLine> checks = reasons.stream()
                .filter(r -> r.layer() == Layer.INSTITUTION && r.result() == Result.CHECK).toList();
        boolean portfolioOnly = checks.stream().allMatch(r -> r.alertId() == null && r.item().equals("포트폴리오"));
        List<String> out = new ArrayList<>();
        if (portfolioOnly) {
            out.add("포트폴리오만 준비해서 내면 지원할 수 있어요.");
        } else {
            out.add("지원하기 전에 챙길 것이 있어요.");
            for (ReasonLine r : checks) {
                if (r.alertId() != null) {
                    out.add("공고 문서끼리 " + r.item() + " 조건이 다르게 적혀 있어 현장실습지원센터에 확인이 필요해요.");
                } else if (r.item().equals("포트폴리오")) {
                    out.add("포트폴리오를 꼭 내야 해요.");
                } else if (r.item().equals(EligibilityRules.CERTIFICATE_ITEM)) {
                    out.add(Josa.iGa(certificate) + " 필요한지 직접 확인해 주세요.");
                } else {
                    out.add(r.item() + " 조건(" + r.requirement() + ")을 직접 확인해 주세요.");
                }
            }
        }
        met(reasons, certificate).ifPresent(m -> out.add(m.mine() + " 나머지 조건(" + m.requirement() + ")은 갖췄어요."));
        return String.join(" ", out);
    }

    private static String eligible(List<ReasonLine> reasons, String certificate) {
        return met(reasons, certificate)
                .map(m -> m.mine() + " 지원 조건(" + m.requirement() + ")을 모두 갖췄어요.")
                .orElse("지원 조건을 모두 갖췄어요.");
    }

    /**
     * 갖춘 기관 조건을 내 값과 함께: mine '3학년·학점 3.4라', requirement '3·4학년, 학점 3.0 이상'. 필수 자격증을 갖췄으면
     * '… 미용 자격증·면허증이 있어'처럼 잇는다. 갖춘 조건이 없으면 빈 값.
     */
    private static Optional<Met> met(List<ReasonLine> reasons, String certificate) {
        List<String> mine = new ArrayList<>();
        List<String> requirement = new ArrayList<>();
        boolean hasCertificate = false;
        for (ReasonLine r : reasons) {
            if (r.layer() != Layer.INSTITUTION || r.result() != Result.MET) {
                continue;
            }
            switch (r.item()) {
                case "학년" -> {
                    mine.add("졸업예정".equals(r.mine()) ? "졸업 예정" : r.mine());
                    requirement.add(r.requirement());
                }
                case "학점" -> {
                    mine.add("학점 " + r.mine());
                    requirement.add("학점 " + r.requirement());
                }
                case EligibilityRules.CERTIFICATE_ITEM -> {
                    hasCertificate = true;
                    requirement.add(certificate);
                }
                default -> {
                }
            }
        }
        if (requirement.isEmpty()) {
            return Optional.empty();
        }
        String values = String.join("·", mine);
        String lead;
        if (hasCertificate) {
            lead = values.isEmpty() ? Josa.iGa(certificate) + " 있어" : Josa.iGo(values) + " " + Josa.iGa(certificate) + " 있어";
        } else {
            lead = Josa.ira(values);
        }
        return Optional.of(new Met(lead, String.join(", ", requirement)));
    }

    private record Met(String mine, String requirement) {
    }

    private static String certificateName(JobRequirement job, Map<String, String> labels) {
        String label = job.certificateCode() == null ? null : labels.get(job.certificateCode());
        if (label != null && !label.isBlank()) {
            return label.strip();
        }
        return job.certificateText() == null || job.certificateText().isBlank() ? "필수 자격증" : job.certificateText().strip();
    }

    /** 앞말 끝소리에 맞춘 조사. 한글은 받침, 숫자는 읽는 소리(0·1·3·6·7·8은 받침), 그 밖의 글자는 받침 없음으로 본다. */
    static final class Josa {

        private Josa() {
        }

        static boolean batchim(String word) {
            // 끝의 괄호 덧말('금융정보공학과(이공대학)')과 따옴표는 읽지 않는다 — 조사는 그 앞말에 붙는다
            String w = word == null ? "" : word.strip().replaceAll("\\([^()]*\\)$", "").replaceAll("['\"’”]+$", "").strip();
            if (w.isEmpty()) {
                return false;
            }
            char c = w.charAt(w.length() - 1);
            if (c >= '가' && c <= '힣') {
                return (c - '가') % 28 != 0;
            }
            return "013678".indexOf(c) >= 0;
        }

        static String eunNeun(String w) {
            return w + (batchim(w) ? "은" : "는");
        }

        static String iGa(String w) {
            return w + (batchim(w) ? "이" : "가");
        }

        /** '전공과' · '학과와'(조사만). */
        static String gwaWa(String w) {
            return batchim(w) ? "과" : "와";
        }

        static String eulReul(String w) {
            return w + (batchim(w) ? "을" : "를");
        }

        /** '3학년이라' · '학점 3.4라'. */
        static String ira(String w) {
            return w + (batchim(w) ? "이라" : "라");
        }

        /** '3학년이고' · '학점 3.4고'. */
        static String iGo(String w) {
            return w + (batchim(w) ? "이고" : "고");
        }

        /** '3학년이에요' · '3.4예요'. */
        static String ieyo(String w) {
            return w + (batchim(w) ? "이에요" : "예요");
        }

        /** '3.5 이상이어야' · '3.5여야'. */
        static String ieoya(String w) {
            return w + (batchim(w) ? "이어야" : "여야");
        }
    }
}
