"""E7 — 경험 글로 현장실습 자리 찾기: AI가 필요한가.

방식
  K   키워드(글자 2·3-gram TF-IDF) — 경험 글 ↔ 공고 글 직접 비교, AI 없음
  A1  AI가 경험 글을 공고 문체 직무 표현으로 바꾼 뒤 K와 같은 검색 (AI는 공고를 안 봄)
  A2  AI가 40개 자리 설명을 읽고 경험이 이어지는 자리 5개를 고르고 근거 구절을 단다
시험 세트
  S1  실제: 선배 수기(2026-2 참여기관과 이어지는 17건)의 실습 내용 → 같은 기관 자리 (기관 단위 정답)
  S2  가상: 학생 말투 '해 본 일' 30개 → 정답 자리(돌리기 전에 고정)
표준 라이브러리만 씀. 키는 환경변수 ANTHROPIC_API_KEY로만 받는다.
"""
import collections, concurrent.futures as cf, json, math, os, re, sys, time, urllib.error, urllib.request

D = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(D, 'out'); os.makedirs(OUT, exist_ok=True)
# 자리·수기는 시드 사본이라 커밋하지 않는다 → build_inputs.py가 out/에 만든다. 가상 경험 세트는 커밋한다
jobs = json.load(open(f'{OUT}/jobs.json', encoding='utf-8'))
ts = json.load(open(f'{OUT}/testimonials.json', encoding='utf-8'))
syn = json.load(open(f'{D}/synthetic.json', encoding='utf-8'))
JOB = {j['id']: j for j in jobs}

def full_doc(j):
    return ' '.join([j['team'], j['title'], j['overview'], j['goal'], j['comp']] + j['plan'])

def squash(s):
    return re.sub(r'[\s\"“”‘’\'·•*\-]+', '', s)

# ---------- K: 글자 n-gram TF-IDF ----------
def grams(s):
    s = re.sub(r'[^0-9A-Za-z가-힣]+', ' ', s.lower())
    c = collections.Counter()
    for w in s.split():
        if len(w) == 1:
            c[w] += 1
        for n in (2, 3):
            for i in range(len(w) - n + 1):
                c[w[i:i + n]] += 1
    return c

DOCS = {j['id']: grams(full_doc(j)) for j in jobs}
DF = collections.Counter(g for c in DOCS.values() for g in c)
NDOC = len(DOCS)
IDF = {g: math.log((1 + NDOC) / (1 + df)) + 1 for g, df in DF.items()}

def vec(c):
    v = {g: (1 + math.log(tf)) * IDF[g] for g, tf in c.items() if g in IDF}
    n = math.sqrt(sum(x * x for x in v.values())) or 1
    return {g: x / n for g, x in v.items()}

DV = {k: vec(c) for k, c in DOCS.items()}

def rank_k(text):
    q = vec(grams(text))
    sc = {k: sum(q.get(g, 0) * x for g, x in dv.items()) for k, dv in DV.items()}
    return sorted(sc, key=lambda k: -sc[k])

# ---------- API ----------
KEY = os.environ.get('ANTHROPIC_API_KEY', '')

def call(model, system, user, max_tokens=1500):
    body = {'model': model, 'max_tokens': max_tokens, 'system': system,
            'messages': [{'role': 'user', 'content': user}],
            # 생각 단계 끔(응답 시간 · 비용을 서비스 조건에 맞춤). haiku는 disabled, sonnet 5.5는 between_tools
            'thinking': {'type': 'disabled' if 'haiku' in model else 'between_tools'}}
    data = json.dumps(body).encode()
    for attempt in range(6):
        req = urllib.request.Request('https://api.anthropic.com/v1/messages', data=data, headers={
            'x-api-key': KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json'})
        t0 = time.time()
        try:
            r = json.load(urllib.request.urlopen(req, timeout=180))
            txt = ''.join(b.get('text', '') for b in r['content'] if b.get('type') == 'text')
            return txt, r.get('usage', {}), time.time() - t0
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:300]
            if e.code in (429, 500, 502, 503, 529):
                time.sleep(3 * (attempt + 1)); continue
            raise RuntimeError(f'{e.code} {msg}')
        except Exception:
            time.sleep(3 * (attempt + 1))
    raise RuntimeError('retries exhausted')

def parse_json(txt):
    a, b = txt.find('{'), txt.rfind('}')
    try:
        return json.loads(txt[a:b + 1])
    except Exception:
        return None

A1_SYS = ("학생이 쓴 경험 글을 채용·실습 공고에서 쓰는 직무 표현으로 바꾼다. 글에 없는 경험은 지어내지 않는다. "
          "경험에서 드러나는 일을 2~6개 뽑아, 각각 quote(학생 글에서 그대로 옮긴 구절)와 job_phrase(공고 문체의 직무 표현, "
          "예: 'SNS 콘텐츠 성과 분석', '재고 관리 및 출고 업무')로 적는다. "
          'JSON만 출력한다: {"phrases":[{"quote":"…","job_phrase":"…"}]}')

def job_card(j):
    return (f"[{j['id']}] {j['team']} · {j['title']}\n"
            f"하는 일: {j['overview'][:260]}\n교육 목표: {j['goal'][:120]}\n요구 역량: {j['comp'][:120]}\n"
            f"주차 계획: {' / '.join(j['plan'])[:260]}")

A2_RULES = ("너는 서경대학교 현장실습지원센터의 직무 상담가다. 학생이 쓴 '해 본 일' 글을 읽고, 위 2026-2 현장실습 자리 중 "
            "그 경험이 실제로 쓰이거나 이어지는 자리를 고른다.\n규칙:\n"
            "- 학과·학년·학점·자격증 같은 지원 조건은 따로 규칙이 거르므로 보지 않는다. 경험과 하는 일의 연결만 본다.\n"
            "- 글에 없는 경험을 지어내지 않는다.\n- 적합한 순서로 5개를 고른다.\n"
            "- 자리마다 student_quote는 학생 글에서 그대로 옮긴 구절(10~40자), job_quote는 그 자리 설명에서 그대로 옮긴 구절(10~40자), "
            "why는 이 경험이 그 자리의 어떤 일에 왜 이어지는지 한 문장.\n"
            '- JSON만 출력한다: {"ranking":[{"id":숫자,"student_quote":"…","job_quote":"…","why":"…"}]}')

def a2_system():
    order = list(reversed(jobs)) if os.environ.get('E7_REVERSE') else jobs   # 순서 영향 확인용
    cards = '\n\n'.join(job_card(j) for j in order)
    return [{'type': 'text', 'text': '# 2026-2 현장실습 자리\n\n' + cards + '\n\n# 할 일\n' + A2_RULES,
             'cache_control': {'type': 'ephemeral'}}]

# ---------- 세트 ----------
def queries():
    qs = []
    for t in ts:
        qs.append({'set': 'S1', 'qid': f"t{t['tid']:02d}", 'text': t['text'],
                   'accept': [j['id'] for j in jobs if j['inst_id'] == t['inst_id']], 'style': 'real'})
    for s in syn:
        qs.append({'set': 'S2', 'qid': s['qid'], 'text': s['text'], 'accept': s['accept'], 'style': s['style']})
    return qs

def run_a1(q, model):
    txt, u, dt = call(model, A1_SYS, q['text'], 800)
    js = parse_json(txt) or {'phrases': []}
    ok = [p for p in js.get('phrases', []) if squash(p.get('quote', '')) and squash(p.get('quote', '')) in squash(q['text'])]
    exp = q['text'] + ' ' + ' '.join(p.get('job_phrase', '') for p in ok)
    return {'qid': q['qid'], 'model': model, 'phrases': js.get('phrases', []), 'n_ok': len(ok),
            'rank': rank_k(exp), 'usage': u, 'sec': dt}

def run_a2(q, model, system):
    txt, u, dt = call(model, system, '학생이 쓴 해 본 일:\n' + q['text'], 1500)
    js = parse_json(txt) or {'ranking': []}
    items = []
    for it in js.get('ranking', [])[:5]:
        try:
            jid = int(it.get('id'))
        except Exception:
            jid = None
        sq, jq = it.get('student_quote', ''), it.get('job_quote', '')
        items.append({'id': jid, 'valid_id': jid in JOB,
                      'sq_ok': bool(squash(sq)) and squash(sq) in squash(q['text']),
                      'jq_ok': jid in JOB and bool(squash(jq)) and squash(jq) in squash(full_doc(JOB[jid])),
                      'student_quote': sq, 'job_quote': jq, 'why': it.get('why', '')})
    return {'qid': q['qid'], 'model': model, 'items': items, 'raw_ok': bool(js.get('ranking')), 'usage': u, 'sec': dt}

def main():
    mode = sys.argv[1]
    qs = queries()
    if mode == 'k':
        rows = [{'qid': q['qid'], 'rank': rank_k(q['text'])} for q in qs]
        json.dump(rows, open(f'{OUT}/k.json', 'w', encoding='utf-8'), ensure_ascii=False)
        print('k done', len(rows)); return
    model = sys.argv[2]
    tag = model.replace('claude-', '') + ('_rev' if os.environ.get('E7_REVERSE') else '')
    if mode == 'a1':
        with cf.ThreadPoolExecutor(6) as ex:
            rows = list(ex.map(lambda q: run_a1(q, model), qs))
        json.dump(rows, open(f'{OUT}/a1_{tag}.json', 'w', encoding='utf-8'), ensure_ascii=False)
    elif mode == 'a2':
        system = a2_system()
        first = run_a2(qs[0], model, system)          # 캐시를 먼저 쓴다
        with cf.ThreadPoolExecutor(6) as ex:
            rest = list(ex.map(lambda q: run_a2(q, model, system), qs[1:]))
        rows = [first] + rest
        json.dump(rows, open(f'{OUT}/a2_{tag}.json', 'w', encoding='utf-8'), ensure_ascii=False)
    print(mode, model, 'done', len(rows))

if __name__ == '__main__':
    main()
