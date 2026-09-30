"""E5: 한국어 임베딩 품질 확인 — 선배 수기로 질의했을 때 그 선배가 다녀온 기관의 직무가 상위 K개에 드는가(Hit@K).

질의 두 가지
  activities : 수기의 '실습 내용' 항목들 (학생이 쓴 글 ↔ 기관이 쓴 직무 설명, 서로 다른 작성자의 의미 매칭)
  major      : 수기의 학과명만 (기획안 M4 검증 원안: '그 학과 프로필'에서 실제 기관이 상위에 드는가)
정답 기준은 기관 단위다. 수기는 과거 학기라 2026-2 직무와 1:1로 같지 않기 때문에, 그 기관의 직무 중 하나라도 상위 K에 들면 맞은 것으로 본다.

사용법
  pip install openpyxl requests
  python hit_at_k.py --jobs-xlsx "<참여기관 리스트.xlsx>" --reviews ../e2_reviews/out --provider tfidf
  VOYAGE_API_KEY=... python hit_at_k.py ... --provider voyage
  OPENAI_API_KEY=... python hit_at_k.py ... --provider openai
결과: e5_report.md (실행할 때마다 제공자별 결과를 덧붙임)
"""
import argparse, json, math, os, pathlib, re, sys
from collections import Counter
from math import comb

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "common"))
from jobs_sheet import read_jobs, canon  # noqa: E402


# ---------- 임베딩 제공자 ----------
def embed_voyage(texts, input_type, model="voyage-4-lite"):
    import requests
    out = []
    for i in range(0, len(texts), 64):
        r = requests.post("https://api.voyageai.com/v1/embeddings",
                          headers={"Authorization": f"Bearer {os.environ['VOYAGE_API_KEY']}"},
                          json={"input": texts[i:i + 64], "model": model, "input_type": input_type}, timeout=60)
        r.raise_for_status()
        out += [d["embedding"] for d in sorted(r.json()["data"], key=lambda d: d["index"])]
    return out


def embed_openai(texts, input_type, model="text-embedding-3-small"):
    import requests
    out = []
    for i in range(0, len(texts), 64):
        r = requests.post("https://api.openai.com/v1/embeddings",
                          headers={"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}"},
                          json={"input": texts[i:i + 64], "model": model}, timeout=60)
        r.raise_for_status()
        out += [d["embedding"] for d in sorted(r.json()["data"], key=lambda d: d["index"])]
    return out


class TfidfChar:
    """API 없이 돌리는 비교 기준: 글자 2~3-gram TF-IDF"""
    def __init__(self, docs):
        self.df = Counter()
        for d in docs:
            self.df.update(set(self._grams(d)))
        self.n = len(docs)

    @staticmethod
    def _grams(t):
        t = re.sub(r"\s+", " ", t)
        return [t[i:i + k] for k in (2, 3) for i in range(len(t) - k + 1)]

    def vec(self, t):
        tf = Counter(self._grams(t))
        return {g: c * math.log((self.n + 1) / (self.df.get(g, 0) + 1)) for g, c in tf.items()}


def cos(a, b):
    if isinstance(a, dict):
        num = sum(v * b.get(k, 0.0) for k, v in a.items())
        na = math.sqrt(sum(v * v for v in a.values())); nb = math.sqrt(sum(v * v for v in b.values()))
    else:
        num = sum(x * y for x, y in zip(a, b))
        na = math.sqrt(sum(x * x for x in a)); nb = math.sqrt(sum(y * y for y in b))
    return num / (na * nb) if na and nb else 0.0


# ---------- 데이터 ----------
def load_reviews(folder):
    rows = []
    for p in sorted(pathlib.Path(folder).glob("*.json")):
        res = json.loads(p.read_text(encoding="utf-8")).get("result") or {}
        for r in res.get("records", []):
            rows.append(r)
    return rows


def job_text(j):
    return f"{j['기관']}\n{j['부서직무']}\n{j['요구역량']}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs-xlsx", required=True)
    ap.add_argument("--reviews", default=str(HERE.parent / "e2_reviews" / "out"))
    ap.add_argument("--provider", choices=["tfidf", "voyage", "openai"], default="tfidf")
    ap.add_argument("--k", type=int, default=5)
    a = ap.parse_args()

    jobs = read_jobs(a.jobs_xlsx)
    job_keys = [canon(j["기관"]) for j in jobs]
    reviews = [r for r in load_reviews(a.reviews) if canon(r["institution"], r["department"]) in set(job_keys)]
    if not reviews:
        sys.exit("2026-2 참여기관과 연결되는 수기가 없습니다. E2 결과(--reviews)를 확인하세요.")
    print(f"직무 {len(jobs)}개, 연결된 수기 {len(reviews)}건, 제공자 {a.provider}")

    queries = {
        "activities": [" / ".join(r["activities"]) or r["results"] for r in reviews],
        "major": [r["major"] for r in reviews],
    }
    docs = [job_text(j) for j in jobs]
    if a.provider == "tfidf":
        model = TfidfChar(docs + queries["activities"] + queries["major"])
        dvec = [model.vec(d) for d in docs]
        qvec = {m: [model.vec(q) for q in qs] for m, qs in queries.items()}
    else:
        fn = embed_voyage if a.provider == "voyage" else embed_openai
        dvec = fn(docs, "document")
        qvec = {m: fn(qs, "query") for m, qs in queries.items()}

    n, k = len(jobs), a.k
    cnt = Counter(job_keys)
    lines = [f"## {a.provider} (K={k}, 직무 {n}개, 수기 {len(reviews)}건)", ""]
    for mode, vecs in qvec.items():
        hits, base, detail = 0, 0.0, []
        for r, qv in zip(reviews, vecs):
            key = canon(r["institution"], r["department"])
            order = sorted(range(n), key=lambda i: -cos(qv, dvec[i]))
            rank = next((pos + 1 for pos, i in enumerate(order) if job_keys[i] == key), None)
            hit = rank is not None and rank <= k
            hits += hit
            m = cnt[key]
            base += 1 - comb(n - m, k) / comb(n, k)       # 무작위로 K개를 뽑았을 때 그 기관 직무가 하나라도 들어갈 확률
            detail.append(f"| {key} | {r['major']} | {rank} | {'O' if hit else 'X'} |")
        lines.append(f"### 질의: {mode}")
        lines.append(f"- Hit@{k}: **{hits}/{len(reviews)} ({hits / len(reviews) * 100:.1f}%)**, 무작위 기대 {base / len(reviews) * 100:.1f}%")
        lines += ["", "| 기관 | 학과 | 그 기관 직무의 최고 순위 | 적중 |", "|---|---|---|---|"] + detail + [""]
    report = HERE / "e5_report.md"
    with open(report, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(l for l in lines if l.startswith(("##", "- Hit"))))
    print("appended", report)


if __name__ == "__main__":
    main()
