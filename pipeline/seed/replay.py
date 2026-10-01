"""모집기간 리플레이용 가상 신호 생성기(ADR-0009·0014).

실제 모집기간의 일별 신호는 없다. 직무별 최종 배정 수(실제 값)만 맞추고 날짜는 시드 고정 난수로 정한다.
화면은 이 값을 '가상'으로 표시한다(isVirtual).

규칙
- 그날 새로 생긴 수만 행으로 만든다(누적 아님). 관심·지원 의사가 둘 다 0인 날은 행이 없다.
- 지원 의사(intent) 누적 = final_assigned. 관심(interest) 누적은 어느 날이든 지원 의사 누적 이상.
- 마감일(closes_on) 당일과 그 뒤에는 행을 만들지 않는다. 회차 종료일 뒤에도 없다.
- 지원 의사는 열린 기간을 같은 길이 구간으로 나눠 구간마다 하나씩 둔다(층화) — 한쪽에 몰리지 않게.
- 관심은 지원 의사마다 같은 날이나 0~2일 앞에 하나씩, 그 밖에 여분을 열린 기간에 고르게 둔다.
- CENTER_CLOSED인데 날짜 기록이 없으면 마감일을 여기서 정한다(closes_on_is_virtual = true).
- 같은 seed면 언제 돌려도 같은 값이다(random.Random(str)은 PYTHONHASHSEED와 무관).
"""
import datetime as dt
import random


def _days(start, end):
    return [start + dt.timedelta(days=i) for i in range((end - start).days + 1)]


def virtual_close(job_id, start, end, seed):
    """날짜 없는 센터 모집마감의 가상 마감일. 회차 1/3 지점 ~ 종료 이틀 전 사이."""
    n = (end - start).days + 1
    rng = random.Random(f"{seed}:close:{job_id}")
    return start + dt.timedelta(days=rng.randint(max(1, n // 3), max(1, n - 2)))


def job_signals(job_id, headcount, assigned, start, end, closes_on, seed):
    """한 직무의 일별 신호 [(date, interest, intent)]. 날짜 오름차순, 0인 날 없음."""
    last = end if closes_on is None else min(end, closes_on - dt.timedelta(days=1))
    if last < start:
        if assigned:
            raise ValueError(f"job {job_id}: 열린 날이 없는데 배정 {assigned}명")
        return []
    days = _days(start, last)
    n = len(days)
    rng = random.Random(f"{seed}:signal:{job_id}")
    intent, interest = [0] * n, [0] * n
    for i in range(assigned):
        d = min(n - 1, int((i + rng.random()) * n / assigned))
        intent[d] += 1
        interest[max(0, d - rng.randint(0, 2))] += 1
    extra = rng.randint(1, assigned + headcount) if assigned else rng.randint(0, headcount + 1)
    for i in range(extra):
        interest[min(n - 1, int((i + rng.random()) * n / extra))] += 1
    return [(days[i], interest[i], intent[i]) for i in range(n) if interest[i] or intent[i]]


def generate(jobs, start, end, seed):
    """jobs: [{id, headcount, final_assigned, closes_on(date|None), center_closed_undated(bool)}]
    → (signals [{job_id, signal_date, interest_count, intent_count}], {job_id: 가상 마감일})"""
    signals, virtual = [], {}
    for j in sorted(jobs, key=lambda j: j["id"]):
        closes_on = j["closes_on"]
        if j.get("center_closed_undated") and closes_on is None:
            closes_on = virtual[j["id"]] = virtual_close(j["id"], start, end, seed)
        for day, interest, intent in job_signals(j["id"], j["headcount"], j["final_assigned"] or 0,
                                                 start, end, closes_on, seed):
            signals.append({"job_id": j["id"], "signal_date": day.isoformat(),
                            "interest_count": interest, "intent_count": intent})
    return signals, virtual
