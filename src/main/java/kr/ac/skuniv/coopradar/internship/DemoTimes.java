package kr.ac.skuniv.coopradar.internship;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.internship.ApplicationRepository.Row;

/**
 * 체험 묶음의 처리 시각(ADR-0041). 체험 학생의 화면은 2026-2 일정의 날(시점·진행 단계, ADR-0033·0040)로 보이므로, 같은 묶음에서
 * 센터가 처리한 시각(접수·매칭·결과 알림·기관 서류)도 그 일정의 날로 남긴다. 체험 학생 지난 기록(가상)과 같은 시각이다.
 * 가입 센터(묶음 없음)는 지금 시각 그대로다.
 */
final class DemoTimes {

    /** 매칭 확정 — 매칭 끝날 17시. */
    static final Instant MATCHED_AT = at(LocalDate.of(2026, 7, 28), 17);
    /** 선발 결과 알림 — 면접·선발 끝날 17시. */
    static final Instant NOTIFIED_AT = at(LocalDate.of(2026, 8, 5), 17);
    /** 기관 서류(평가표·출근부) 받음 · 학점 인정 승인 요청 · 안 낸 학생 알림 — 후기 간담회 전날 10시. */
    static final Instant CLOSED_AT = at(LocalDate.of(2026, 12, 16), 10);

    private DemoTimes() {
    }

    static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(Times.KST).toInstant();
    }

    /** 접수·보완 요청 — 낸 시각 1분 뒤. 낸 시각이 없으면 지금. */
    static Instant received(Row r, Instant now) {
        return r.submittedAt() == null ? now : r.submittedAt().toInstant().plusSeconds(60);
    }
}
