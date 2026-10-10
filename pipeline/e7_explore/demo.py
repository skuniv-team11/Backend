"""캔버스 예시 학생의 실제 AI 출력: 지원 조건을 맞춘 23곳 → AI 순서·근거 → 상위 3곳 '왜 맞나요'."""
import concurrent.futures as cf, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e7
CAND = [102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,120,121,122,123,124,135,136,137]  # 규칙 판정 '지원 불가'를 뺀 자리
ST = {'exps': ['학과 졸업작품 홍보용 인스타그램 계정을 1년 동안 운영하면서, 게시 시간과 첫 문장을 바꿔 가며 저장 수를 비교했어요.',
               '화장품 매장 아르바이트에서 손님이 자주 묻는 질문을 메모해 두고 진열을 바꿔 봤어요.'],
      'wants': ['자사 SNS 채널 콘텐츠 운영 및 홍보 마케팅 업무 지원', '인플루언서 소통 및 관리', '브랜드 숏폼 콘텐츠 기획', '기획전 제작 및 운영'],
      'interests': ['뷰티 브랜드 마케팅', '뷰티 콘텐츠']}
def student_text():
    return (''.join(f"해 본 일 {i+1}: {t}\n" for i, t in enumerate(ST['exps']))
            + f"하고 싶은 일(고른 카드): {' / '.join(ST['wants'])}\n관심 분야: {', '.join(ST['interests'])}")
SRC_S = e7.squash(' '.join(ST['exps'] + ST['wants'] + ST['interests']))
RANK_RULES = ("너는 서경대학교 현장실습지원센터의 직무 상담가다. 위 자리는 이 학생이 지원 조건을 이미 맞춘 자리만 모은 것이다. "
    "학생의 해 본 일 · 하고 싶은 일 · 관심 분야를 읽고, 이어지는 자리를 적합한 순서로 5개 고른다.\n규칙:\n"
    "- 위 목록에 있는 자리 번호만 쓴다.\n- 글에 없는 경험을 지어내지 않고, 학과 · 전공에서 능력을 추론하지 않는다.\n"
    "- 자리마다 student_quote(학생 글에서 그대로, 10~40자), job_quote(그 자리 설명의 한 줄 안에서 그대로, 10~40자), why(한 문장).\n"
    '- JSON만 출력한다: {"ranking":[{"id":숫자,"student_quote":"…","job_quote":"…","why":"…"}]}')
WHY_SYS = ("너는 서경대학교 현장실습지원센터의 직무 상담가다. 학생 글과 자리 설명만 근거로, 이 자리가 왜 이 학생에게 맞는지 학생에게 직접 설명한다.\n규칙:\n"
    "- 지원 조건 · 선호 전공은 화면에 따로 나오므로 말하지 않는다.\n"
    "- 학생 글에 없는 경험 · 능력 · 성향을 지어내거나 학과 · 전공에서 추론하지 않는다. 공고에 없는 업무를 지어내지 않는다.\n"
    "- 나열하지 말고 '학생이 한 일 → 이 자리의 어떤 일에 → 왜 도움이 되는지'를 한 문장에 잇는다. 이어지는 근거가 약하면 억지로 늘리지 말고 why를 줄인다.\n"
    "- 근거 구절은 원문의 한 줄 안에서 그대로 옮긴다(student는 학생 글에서, job은 자리 설명에서, 8~40자). 여러 줄을 ' / '로 붙이지 않는다.\n"
    "- 해요체, 문장마다 70자 안쪽.\n"
    '- JSON만 출력한다: {"summary":"한 문장","why":[{"text":"…","student":"…","job":"…"}],'
    '"new":[{"text":"이 실습에서 새로 해 볼 일","job":"…"}],"prepare":{"text":"미리 채우면 좋은 것 한 문장","job":"…"}}  why 2~3개, new 1~2개.')
ok = lambda q, src: bool(e7.squash(q or '')) and e7.squash(q) in src
def rank():
    cards = '\n\n'.join(e7.job_card(e7.JOB[i]) for i in CAND)
    sysm = [{'type': 'text', 'text': '# 지원 조건을 맞춘 자리\n\n' + cards + '\n\n# 할 일\n' + RANK_RULES}]
    txt, u, dt = e7.call('claude-sonnet-5-5', sysm, student_text(), 1500)
    js = e7.parse_json(txt) or {'ranking': []}
    out = []
    for it in js['ranking']:
        jid = int(it['id']); j = e7.JOB.get(jid)
        out.append(dict(it, id=jid, in_cand=jid in CAND, sq_ok=ok(it.get('student_quote'), SRC_S), jq_ok=bool(j) and ok(it.get('job_quote'), e7.squash(e7.full_doc(j)))))
    return {'sec': round(dt, 1), 'usage': u, 'ranking': out}
def why(jid):
    j = e7.JOB[jid]
    txt, u, dt = e7.call('claude-sonnet-5-5', WHY_SYS, student_text() + f"\n\n# 자리 [{jid}] {j['team']} · {j['title']}\n" + e7.full_doc(j), 1200)
    js = e7.parse_json(txt) or {}
    sj = e7.squash(e7.full_doc(j)); n = g = 0
    for w in js.get('why', []): n += 2; g += ok(w.get('student'), SRC_S) + ok(w.get('job'), sj)
    for w in js.get('new', []) + [js.get('prepare') or {}]:
        if w: n += 1; g += ok(w.get('job'), sj)
    return {'id': jid, 'sec': round(dt, 1), 'quotes': f'{g}/{n}', 'out': js, 'usage': u}
if __name__ == '__main__':
    r = rank()
    top = [x['id'] for x in r['ranking'] if x['in_cand'] and x['sq_ok'] and x['jq_ok']][:3]
    with cf.ThreadPoolExecutor(3) as ex:
        ws = list(ex.map(why, top))
    json.dump({'rank': r, 'why': ws}, open(os.path.join(e7.OUT, 'demo.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(json.dumps({'rank': r, 'why': ws}, ensure_ascii=False, indent=1))
