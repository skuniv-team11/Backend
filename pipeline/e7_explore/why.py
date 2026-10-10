"""직무 상세 '왜 맞나요' — AI가 학생 경험과 공고를 이어 적합 이유를 쓰고, 코드가 인용 구절을 원문과 대조한다."""
import concurrent.futures as cf, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e7

STUDENT = {
    'major': '메이크업디자인학과 3학년(5학기 이수)', 'gpa': 3.4, 'cert': '없음',
    'interests': ['뷰티 브랜드 마케팅', '뷰티 콘텐츠'],
    'exps': ['학과 졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서, 게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요.',
             '화장품 매장 아르바이트에서 손님이 자주 묻는 질문을 메모해 두고 진열을 바꿔 봤어요.'],
    'wants': ['자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원', '인플루언서 소통 및 관리', '브랜드 숏폼 콘텐츠 기획', '기획전 제작 및 운영'],
}

def verdict(j):
    # 예시 학생에 대한 규칙 판정(지금 /api/eligibility와 같은 항목만 간단히)
    if j['grade_rule'] in ('Y4', 'GRADUATING'):
        return '지원 불가(학년)'
    return '지원 가능'

SYS = ("너는 서경대학교 현장실습지원센터의 직무 상담가다. 아래 학생 정보와 자리 정보만 근거로, 이 자리가 이 학생에게 왜 맞는지 "
       "(덜 맞으면 덜 맞는 이유도) 학생에게 직접 설명한다.\n규칙:\n"
       "- 지원 조건 판정과 선호 전공 여부는 규칙이 이미 정했다. 바꾸거나 새로 판단하지 않고, 필요하면 그대로 언급만 한다.\n"
       "- 학생 글에 없는 경험, 공고에 없는 업무를 지어내지 않는다. 단순 나열이 아니라 '학생이 한 일 → 이 자리의 어떤 일에 → 왜 도움이 되는지'를 잇는다.\n"
       "- 근거 구절은 원문에서 그대로 옮긴다(student는 학생 글·고른 일에서, job은 자리 설명에서, 8~40자).\n"
       "- 해요체, 문장마다 70자 안쪽.\n"
       '- JSON만 출력한다: {"fit":"잘 맞아요|맞아요|조금 맞아요|잘 안 맞아요","summary":"한 문장",'
       '"why":[{"text":"…","student":"…","job":"…"}],"new":[{"text":"이 실습에서 새로 해 볼 일","job":"…"}],'
       '"prepare":{"text":"미리 채우면 좋은 것 한 문장","job":"…"}}  why는 2~3개, new는 1~2개.')

def user_msg(j):
    st = STUDENT
    jd = e7.full_doc(j)
    return (f"# 학생\n학과·학년: {st['major']}\n관심 분야: {', '.join(st['interests'])}\n"
            + ''.join(f"해 본 일 {i+1}: {t}\n" for i, t in enumerate(st['exps']))
            + f"고른 하고 싶은 일: {' / '.join(st['wants'])}\n\n"
            f"# 규칙 결과\n지원 조건: {verdict(j)}\n선호 전공: {j['major_text'] or '무관'} (학생 학과는 목록에 {'있음' if '메이크업' in (j['major_text'] or '') else '없음'})\n\n"
            f"# 자리 [{j['id']}] {j['team']} · {j['title']}\n{jd}")

def check(js, j):
    src_s = e7.squash(' '.join(STUDENT['exps'] + STUDENT['wants']))
    src_j = e7.squash(e7.full_doc(j))
    ok = lambda q, src: bool(e7.squash(q or '')) and e7.squash(q) in src
    n = good = 0
    for w in js.get('why', []):
        n += 2; good += ok(w.get('student'), src_s) + ok(w.get('job'), src_j)
    for w in js.get('new', []):
        n += 1; good += ok(w.get('job'), src_j)
    p = js.get('prepare') or {}
    if p:
        n += 1; good += ok(p.get('job'), src_j)
    return good, n

def run(model, jid):
    j = e7.JOB[jid]
    txt, u, dt = e7.call(model, SYS, user_msg(j), 1200)
    js = e7.parse_json(txt) or {}
    g, n = check(js, j)
    return {'model': model, 'id': jid, 'sec': round(dt, 1), 'quotes_ok': g, 'quotes': n, 'out': js, 'usage': u}

if __name__ == '__main__':
    ids = [122, 123, 103, 106, 124]
    jobs_ = [(m, i) for m in ['claude-sonnet-5-5', 'claude-haiku-5-5'] for i in ids]
    with cf.ThreadPoolExecutor(5) as ex:
        rows = list(ex.map(lambda a: run(*a), jobs_))
    json.dump(rows, open(os.path.join(e7.OUT, 'why.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    for r in rows:
        print(r['model'][7:], r['id'], f"{r['sec']}초", f"구절 {r['quotes_ok']}/{r['quotes']}", r['out'].get('fit'), '|', r['out'].get('summary'))
