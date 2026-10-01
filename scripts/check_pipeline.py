#!/usr/bin/env python3
"""pipeline 검사: 파이썬 문법 + 구조화 출력(JSON 스키마) 제약 + 시드(단위 테스트, R__seed.sql ↔ seed.json).

제약은 Claude structured outputs 문서(2026-09) 기준이다. 어기면 API가 400을 내거나 결과가 어긋난다(ADR-0003).
실패하면 무엇을 어떻게 고칠지 출력하고 1로 끝난다.
"""
import json, os, pathlib, py_compile, subprocess, sys

# Windows 기본 콘솔은 cp949라 ✓·✗ 에서 UnicodeEncodeError 로 죽는다. CI(리눅스)는 영향 없다.
sys.stdout.reconfigure(encoding="utf-8")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PIPE = ROOT / "pipeline"
FORBIDDEN = {"minLength", "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum",
             "exclusiveMaximum", "multipleOf", "oneOf", "anyOf", "not", "if", "then", "else"}
errors = []


def err(where, what, fix):
    errors.append(f"✗ {where}: {what}\n  → {fix}")


for py in sorted(PIPE.rglob("*.py")):
    try:
        py_compile.compile(str(py), doraise=True)
    except py_compile.PyCompileError as e:
        err(py.relative_to(ROOT), "문법 오류", str(e).splitlines()[-1])


def walk(node, path, src):
    if isinstance(node, dict):
        for k in FORBIDDEN & set(node):
            err(src, f"{path} 에 '{k}' 사용", "지원되지 않는 키워드입니다. 값 검증은 코드(score.py 등)로 옮기세요")
        t = node.get("type")
        if isinstance(t, list):
            err(src, f"{path} type 이 배열 {t}", "null 허용 대신 빈 문자열/0 을 쓰고 type 을 하나로 두세요")
        if t == "object":
            props = set(node.get("properties", {}))
            if node.get("additionalProperties") is not False:
                err(src, f"{path} additionalProperties 가 false 가 아님", "build_schema.py 의 obj() 로 만들면 자동으로 false 가 됩니다")
            missing = props - set(node.get("required", []))
            if missing:
                err(src, f"{path} required 에 빠진 속성 {sorted(missing)}", "모든 속성을 required 로 두세요(선택 속성은 전체 24개 한도)")
        if node.get("type") == "array" and node.get("minItems", 0) not in (0, 1):
            err(src, f"{path} minItems={node['minItems']}", "minItems 는 0 또는 1만 지원됩니다")
        for k, v in node.items():
            walk(v, f"{path}.{k}", src)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, f"{path}[{i}]", src)


# 같은 시스템 프롬프트를 파이썬(실험)과 Java(서비스)가 각각 들고 있다. 한쪽만 고치면 E1과 E3가 달라진다.
PROMPT_PAIRS = [(PIPE / "e1_operation_plan" / "prompt_system.md",
                 ROOT / "src" / "main" / "resources" / "prompts" / "operation-plan-system.md")]
for a, b in PROMPT_PAIRS:
    ra, rb = a.relative_to(ROOT).as_posix(), b.relative_to(ROOT).as_posix()
    if not b.exists():
        err(rb, "파일이 없음", f"cp {ra} {rb}")
    elif a.read_text(encoding="utf-8") != b.read_text(encoding="utf-8"):
        err(rb, f"{ra} 와 내용이 다름", f"둘은 같아야 합니다. 고친 쪽을 다른 쪽에 복사하세요: cp {ra} {rb}")

schemas = sorted(PIPE.rglob("schema*.json"))
for s in schemas:
    try:
        walk(json.loads(s.read_text(encoding="utf-8")), "$", s.relative_to(ROOT))
    except json.JSONDecodeError as e:
        err(s.relative_to(ROOT), "JSON 파싱 실패", str(e))

# 시드(ADR-0014): 단위 테스트 + R__seed.sql이 seed.json에서 만든 그대로인지(손으로 고치지 않았는지)
env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
seed_dir = PIPE / "seed"
r = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(seed_dir), "-p", "test_*.py"],
                   capture_output=True, text=True, encoding="utf-8", env=env)
if r.returncode:
    err("pipeline/seed", "단위 테스트 실패\n" + r.stderr.strip(), "python -m unittest discover -s pipeline/seed -v 로 실패한 테스트를 보세요")
r = subprocess.run([sys.executable, str(seed_dir / "to_sql.py"), "--check"],
                   capture_output=True, text=True, encoding="utf-8", env=env)
if r.returncode:
    err("src/main/resources/db/migration/R__seed.sql", "seed.json과 다름", "python pipeline/seed/to_sql.py 로 다시 만들어 같이 커밋하세요(손으로 고치지 않는다)")

if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"✓ pipeline 검사 통과 (파이썬 {len(list(PIPE.rglob('*.py')))}개, 스키마 {len(schemas)}개)")
