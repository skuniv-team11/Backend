"""모집기간 리플레이용 가상 신호 생성기(ADR-0009·0014).

실제 모집기간의 일별 신호는 없다. 직무별 최종 배정 수(실제 값)만 맞추고 날짜는 시드 고정 난수로 정한다.
화면은 이 값을 '가상'으로 표시한다(isVirtual).

규칙
- 신호는 관심(interest) 하나다 — 관심 = 내 지망에 담은 사람 수(ADR-0019). 예전의 지원 의사·관심 두 숫자는 없앴다.
- 그날 새로 생긴 수만 행으로 만든다(누적 아님). 관심이 0인 날은 행이 없다.
- 관심 누적 = final_assigned(최종 배정 수, 실제 값).
- 마감일(closes_on) 당일과 그 뒤에는 행을 만들지 않는다. 회차 종료일 뒤에도 없다.
- 관심은 열린 기간을 같은 길이 구간으로 나눠 구간마다 하나씩 둔다(층화) — 한쪽에 몰리지 않게.
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
    """한 직무의 일별 관심 [(date, interest)]. 날짜 오름차순, 0인 날 없음."""
    last = end if closes_on is None else min(end, closes_on - dt.timedelta(days=1))
    if last < start:
        if assigned:
            raise ValueError(f"job {job_id}: 열린 날이 없는데 배정 {assigned}명")
        return []
    days = _days(start, last)
    n = len(days)
    rng = random.Random(f"{seed}:signal:{job_id}")
    interest = [0] * n
    for i in range(assigned):
        interest[min(n - 1, int((i + rng.random()) * n / assigned))] += 1
        rng.randint(0, 2)  # 예전 '관심'(없앰) 날짜에 쓰던 난수. 버려도 뽑아 둬야 10/5 전과 같은 날짜가 나온다
    return [(days[i], interest[i]) for i in range(n) if interest[i]]


def generate(jobs, start, end, seed):
    """jobs: [{id, headcount, final_assigned, closes_on(date|None), center_closed_undated(bool)}]
    → (signals [{job_id, signal_date, interest_count}], {job_id: 가상 마감일})"""
    signals, virtual = [], {}
    for j in sorted(jobs, key=lambda j: j["id"]):
        closes_on = j["closes_on"]
        if j.get("center_closed_undated") and closes_on is None:
            closes_on = virtual[j["id"]] = virtual_close(j["id"], start, end, seed)
        for day, interest in job_signals(j["id"], j["headcount"], j["final_assigned"] or 0,
                                         start, end, closes_on, seed):
            signals.append({"job_id": j["id"], "signal_date": day.isoformat(), "interest_count": interest})
    return signals, virtual
