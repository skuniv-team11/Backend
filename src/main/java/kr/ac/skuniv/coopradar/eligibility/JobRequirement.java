package kr.ac.skuniv.coopradar.eligibility;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import kr.ac.skuniv.coopradar.job.InstitutionRef;

/**
 * 판정에 쓰는 직무 1개의 요건(시드 job + 선호 전공 매핑 + 판정 항목에 걸린 검토 알림).
 *
 * @param course                 VACATION이면 졸업예정자 계절제 규정이 붙는다
 * @param gradeRule              Y3_4 · Y4 · GRADUATING
 * @param gpaMin                 학점 하한. 없으면 null
 * @param portfolio              REQUIRED · PREFERRED · NONE
 * @param certificate            REQUIRED · PREFERRED · NONE
 * @param majorDepartmentIds     선호 전공 표기에서 사람이 확정한 학과 id(M3). 확정 안 된 표기는 들어 있지 않다
 * @param alerts                 판정 항목(학년·학점·전공·포트폴리오·자격증·과정)에 걸린 검토 알림
 * @param listSeq                센터 참여기관 리스트 순번(목록 정렬용)
 */
public record JobRequirement(
        int jobId,
        int listSeq,
        String title,
        String team,
        InstitutionRef institution,
        String course,
        String gradeRule,
        BigDecimal gpaMin,
        String portfolio,
        String certificate,
        String certificateText,
        String majorText,
        boolean majorOpen,
        Set<Integer> majorDepartmentIds,
        List<AlertRef> alerts) {

    public record AlertRef(int id, String kind, String fieldKey) {
    }
}
