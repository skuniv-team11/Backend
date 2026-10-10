import glob, json, math, os, statistics, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e7

Q = {q['qid']: q for q in e7.queries()}
OUT = e7.OUT

def comb(n, k):
    return math.comb(n, k) if 0 <= k <= n else 0

def rand_hit(q, k):
    n = len(q['accept'])
    return 1 - comb(40 - n, k) / comb(40, k)

def metrics(rows, rank_of):
    out = {}
    for name, flt in [('S1 실제 수기 17', lambda q: q['set'] == 'S1'),
                      ('S2 가상 30', lambda q: q['set'] == 'S2'),
                      ('S2-A 직무 용어 섞임', lambda q: q['set'] == 'S2' and q['style'] == 'A'),
                      ('S2-B 생활 말', lambda q: q['set'] == 'S2' and q['style'] == 'B')]:
        sel = [r for r in rows if flt(Q[r['qid']])]
        if not sel:
            continue
        h1 = h3 = rr = 0
        for r in sel:
            rk = rank_of(r)
            acc = set(Q[r['qid']]['accept'])
            pos = next((i for i, x in enumerate(rk) if x in acc), None)
            h1 += pos is not None and pos < 1
            h3 += pos is not None and pos < 3
            rr += 1 / (pos + 1) if pos is not None and pos < 5 else 0
        rnd3 = sum(rand_hit(Q[r['qid']], 3) for r in sel)
        out[name] = (len(sel), h1, h3, rr / len(sel), rnd3)
    return out

def show(title, m):
    print(f'\n## {title}')
    for k, (n, h1, h3, mrr, rnd3) in m.items():
        print(f'  {k:16s} n={n:2d}  Hit@1 {h1:2d} ({h1/n:4.0%})  Hit@3 {h3:2d} ({h3/n:4.0%})  MRR@5 {mrr:.2f}  | 무작위 Hit@3 기대 {rnd3:4.1f}')

def usage(rows):
    it = sum(r['usage'].get('input_tokens', 0) for r in rows)
    cr = sum(r['usage'].get('cache_read_input_tokens', 0) for r in rows)
    cw = sum(r['usage'].get('cache_creation_input_tokens', 0) for r in rows)
    ot = sum(r['usage'].get('output_tokens', 0) for r in rows)
    secs = [r['sec'] for r in rows]
    return f'입력 {it:,} · 캐시 읽기 {cr:,} · 캐시 쓰기 {cw:,} · 출력 {ot:,} 토큰 | 응답 중앙값 {statistics.median(secs):.1f}초 · 최대 {max(secs):.1f}초'

k = json.load(open(f'{OUT}/k.json', encoding='utf-8'))
show('K 키워드만 (AI 없음)', metrics(k, lambda r: r['rank']))
for f in sorted(glob.glob(f'{OUT}/a1_*.json')):
    rows = json.load(open(f, encoding='utf-8'))
    show(f'A1 AI 바꾸기 → 키워드 [{os.path.basename(f)[3:-5]}]', metrics(rows, lambda r: r['rank']))
    print('  구절 검증 통과', sum(r['n_ok'] for r in rows), '/', sum(len(r['phrases']) for r in rows), '|', usage(rows))
for f in sorted(glob.glob(f'{OUT}/a2_*.json')):
    rows = json.load(open(f, encoding='utf-8'))
    tag = os.path.basename(f)[3:-5]
    show(f'A2 AI가 자리 설명을 읽고 고름 [{tag}]', metrics(rows, lambda r: [i['id'] for i in r['items']]))
    show(f'A2 검증 통과한 것만 [{tag}]', metrics(rows, lambda r: [i['id'] for i in r['items'] if i['valid_id'] and i['sq_ok'] and i['jq_ok']]))
    items = [i for r in rows for i in r['items']]
    print(f"  항목 {len(items)} · id 유효 {sum(i['valid_id'] for i in items)} · 학생 구절 원문 일치 {sum(i['sq_ok'] for i in items)} · "
          f"공고 구절 원문 일치 {sum(i['jq_ok'] for i in items)} · 둘 다 {sum(i['sq_ok'] and i['jq_ok'] for i in items)} | JSON 실패 {sum(not r['raw_ok'] for r in rows)}")
    print('  ', usage(rows))
