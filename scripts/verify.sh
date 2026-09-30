#!/usr/bin/env bash
# 전체 검증의 단일 진입점. 사람·코딩 도구·CI가 같은 검사를 쓴다.
#   scripts/verify.sh        main 대비 바뀐 영역만
#   scripts/verify.sh --all  전부
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

all=false; [ "${1:-}" = "--all" ] && all=true
if ! $all && git rev-parse --git-dir >/dev/null 2>&1; then
  base=$(git merge-base HEAD origin/main 2>/dev/null || git rev-parse HEAD 2>/dev/null || echo "")
  changed=$( { [ -n "$base" ] && git diff --name-only "$base"; git diff --name-only; git diff --name-only --cached; git ls-files --others --exclude-standard; } | sort -u)
else
  all=true; changed=""
fi
touched() { $all || echo "$changed" | grep -qE "$1"; }

results=(); status=0
run() {  # run <이름> <실패 시 안내> <명령...>
  local name="$1" hint="$2"; shift 2
  echo "── $name"
  if "$@"; then results+=("✓ $name"); else results+=("✗ $name — $hint"); status=1; fi
}

run "비밀·원본 파일" "출력된 파일을 git rm --cached 하고 값은 환경변수로 옮기세요" scripts/check-secrets.sh --all
run "커밋 메시지" "공동 작성자·도구 표기 줄을 지우고 git commit --amend 로 다시 쓰세요" scripts/check-commits.sh "$(git merge-base HEAD origin/main 2>/dev/null)" HEAD
touched '^(src/|build\.gradle|settings\.gradle|gradle/|scripts/verify)' && \
  run "backend 테스트" "build/reports/tests/test/index.html 에서 실패한 테스트를 보세요" ./gradlew test -q
touched '^(pipeline/|scripts/)' && \
  run "pipeline 검사" "출력된 → 안내를 따르세요(스키마는 build_schema.py 를 고쳐 다시 생성)" python3 scripts/check_pipeline.py
touched '^(scripts/|\.githooks/)' && \
  run "하네스 자체 테스트" "규칙이 막아야 할 것을 못 막거나, 통과해야 할 것을 막고 있습니다" scripts/test-harness.sh

echo; printf '%s\n' "${results[@]}"
exit $status
