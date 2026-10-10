"""날짜 없는 센터 모집마감의 가상 마감일(ADR-0009·0014).

참여기관 리스트에 '센터 모집마감'만 있고 날짜가 없는 직무는, 회차 안에서 시드 고정 난수로 마감일을 정한다
(closes_on_is_virtual = true, 화면은 '가상 마감일'로 표시). 같은 seed면 언제 돌려도 같은 날이다
(random.Random(str)은 PYTHONHASHSEED와 무관).

예전에는 모집기간 리플레이 가상 관심(replay_signal)도 여기서 만들었다. 모집 신호를 걷어내며(ADR-0036) 지웠고,
마감일 난수는 그때와 같은 값이 나오게 그대로 둔다.
"""
import datetime as dt
import random


def virtual_close(job_id, start, end, seed):
    """날짜 없는 센터 모집마감의 가상 마감일. 회차 1/3 지점 ~ 종료 이틀 전 사이."""
    n = (end - start).days + 1
    rng = random.Random(f"{seed}:close:{job_id}")
    return start + dt.timedelta(days=rng.randint(max(1, n // 3), max(1, n - 2)))


def virtual_closes(jobs, start, end, seed):
    """jobs: [{id, closes_on(date|None), center_closed_undated(bool)}] → {job_id: 가상 마감일}"""
    return {j["id"]: virtual_close(j["id"], start, end, seed) for j in sorted(jobs, key=lambda j: j["id"])
            if j.get("center_closed_undated") and j["closes_on"] is None}
