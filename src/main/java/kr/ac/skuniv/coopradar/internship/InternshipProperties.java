package kr.ac.skuniv.coopradar.internship;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 현장실습 진행 설정(application.yml {@code app.internship}, ADR-0033). 값의 출처는 application.yml 주석에 있다.
 *
 * @param essayMinChars      자기소개서 문항마다 최소 글자 수(제5호 서식 '300자 이상 작성')
 * @param course             교과목 이름(제5호 서식 '표준 현장실습 D')
 * @param credits            인정 학점(학생 모집안내 12학점)
 * @param scholarshipMonthly 대학 지원금 월액(원, 학생 모집안내 월 20만 원)
 * @param scholarshipMonths  대학 지원금 최대 개월(학생 모집안내 최대 3개월)
 * @param applyingToday      체험 학생 '지원 중' 기준일
 * @param practicingToday    체험 학생 '실습 중' 기준일
 * @param doneToday          체험 학생 '실습 마친 뒤' 기준일
 */
@ConfigurationProperties("app.internship")
public record InternshipProperties(int essayMinChars, String course, int credits, int scholarshipMonthly,
                                   int scholarshipMonths, LocalDate applyingToday, LocalDate practicingToday,
                                   LocalDate doneToday) {
}
