#!/usr/bin/env bash
# 공개 저장소에 올라가면 안 되는 것(API 키, 원본 데이터, .env)을 찾는다.
#   scripts/check-secrets.sh --staged   커밋하려는 변경(pre-commit 훅)
#   scripts/check-secrets.sh --all      추적 중인 전체 파일(CI)
#   scripts/check-secrets.sh 파일...    지정한 파일
# 발견하면 이유와 고치는 방법을 출력하고 1로 끝난다.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

mode="${1:---staged}"
if [ "$mode" = "--staged" ]; then
  mapfile -t files < <(git diff --cached --name-only --diff-filter=ACMR)
elif [ "$mode" = "--all" ]; then
  mapfile -t files < <(git ls-files)
else
  files=("$@")
fi

fail=0
report() { echo "✗ $1"; echo "  → $2"; fail=1; }

# 1) 들어오면 안 되는 파일 종류
for f in "${files[@]}"; do
  case "$f" in
    *.pdf|*.PDF|*.xlsx|*.xls|*.hwp|*.hwpx)
      report "$f: 원본 문서 파일은 공개 저장소에 올리지 않습니다" "git rm --cached \"$f\" 후 저장소 밖(예: data/raw/, .gitignore 대상)에 두세요" ;;
    .env|*/.env|.env.*|*/.env.*)
      case "$f" in *.env.example) ;; *)
        report "$f: .env 파일은 커밋하지 않습니다" "git rm --cached \"$f\" 후 값은 .env.example 에 자리표시자로만 적으세요" ;; esac ;;
    data/raw/*)
      report "$f: data/raw/ 는 원본 보관용입니다" "git rm --cached \"$f\"" ;;
  esac
  # 루트의 숨김 파일·폴더와 문서는 허용 목록만. 개인 편집기·도구 설정과 개인 메모가 공개 저장소에 섞이지 않게 한다.
  case "${f%%/*}" in
    .github|.githooks|.gitignore|.gitattributes|.editorconfig|.dockerignore|.env.example|.oxlintrc.json|.nvmrc) ;;
    .env|.env.*) ;;   # 위에서 따로 알림
    .*) report "$f: 개인 도구·편집기 설정은 올리지 않습니다" "git rm --cached \"$f\" 후 .git/info/exclude 에 경로를 적으세요. 팀이 같이 쓸 설정이면 이 파일의 허용 목록에 이유와 함께 추가합니다" ;;
  esac
  case "$f" in
    */*|README.md|AGENTS.md) ;;
    *.md|*.MD) report "$f: 루트 문서는 README.md·AGENTS.md만 둡니다" "개인 메모면 git rm --cached 후 .git/info/exclude 에, 팀 문서면 docs/ 로 옮기세요" ;;
  esac
done

# 2) 키처럼 보이는 문자열 (자리표시자 sk-ant-... , ${VAR}, <키> 는 통과)
patterns=(
  'sk-ant-[A-Za-z0-9_-]{20,}::Anthropic API 키'
  'sk-(proj-)?[A-Za-z0-9_-]{32,}::OpenAI API 키'
  '(^|[^A-Za-z0-9])pa-[A-Za-z0-9_-]{30,}::Voyage API 키'
  'gh[pousr]_[A-Za-z0-9]{36,}::GitHub 토큰'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----::개인 키'
  '(ANTHROPIC_API_KEY|VOYAGE_API_KEY|OPENAI_API_KEY|NTS_SERVICE_KEY|NCS_SERVICE_KEY|ODSAY_API_KEY|DB_PASSWORD)[[:space:]]*[:=][[:space:]]*["'"'"']?[^[:space:]"'"'"'$<{.]{16,}::환경변수에 실제 값 대입'
  'serviceKey=[A-Za-z0-9%+/=]{40,}::공공데이터포털 서비스 키'
)
self="scripts/check-secrets.sh"
for f in "${files[@]}"; do
  [ "$f" = "$self" ] && continue
  [ -f "$f" ] || continue
  grep -Iq . "$f" 2>/dev/null || continue          # 바이너리 건너뜀
  if [ "$mode" = "--staged" ]; then content=$(git show ":$f" 2>/dev/null); else content=$(cat "$f"); fi
  for p in "${patterns[@]}"; do
    re="${p%::*}"; label="${p##*::}"
    if line=$(printf '%s\n' "$content" | grep -nE -- "$re" | head -1) && [ -n "$line" ]; then
      report "$f:${line%%:*}: $label 로 보이는 값" "값을 지우고 환경변수로 옮기세요. 이미 push 했다면 그 키는 노출된 것이니 즉시 폐기·재발급하세요"
    fi
  done
done

if [ $fail -eq 0 ]; then echo "✓ 비밀·원본 파일 검사 통과 (${#files[@]}개 파일)"; fi
exit $fail
