#!/usr/bin/env bash
# 커밋 메시지 검사. commit-msg 훅과 CI(check-commits.sh)가 같이 쓴다.
#   scripts/check-commit-msg.sh <메시지 파일>
# 커밋 작성자는 실제로 작업한 팀원 한 명이다(ADR-0006). 그래서
#   - 메시지 끝에 공동 작성자·서명·링크 트레일러(`Co-authored-by: 이름 <메일>`, `X-Session: URL` 등)를 넣지 않는다.
#     이슈 연결(Refs/Closes/Fixes/Resolves)만 쓴다.
#   - "Generated with …", "Created by …" 같은 생성 도구 표기 줄을 넣지 않는다.
# 발견하면 이유와 고치는 방법을 출력하고 1로 끝난다.
set -uo pipefail
msg_file="${1:?메시지 파일 경로가 필요합니다}"
body=$(grep -v '^#' "$msg_file")          # git이 커밋할 때 지우는 주석 줄은 빼고 본다
fail=0
report() { echo "✗ $1"; echo "  → $2"; fail=1; }

# 트레일러 중 사람·링크를 붙이는 형식(키에 하이픈, 값에 <이메일>이나 URL)을 막는다. "Note: …" 같은 평범한 문장은 통과.
while IFS= read -r line; do
  [ -z "$line" ] && continue
  key=$(printf '%s' "${line%%:*}" | tr '[:upper:]' '[:lower:]'); value="${line#*:}"
  case "$key" in refs|closes|fixes|resolves) continue ;; esac
  if [[ "$key" == *-* ]] || printf '%s' "$value" | grep -qE '<[^>]*@[^>]*>|https?://'; then
    report "허용하지 않은 트레일러: $line" \
      "메시지 끝에는 공동 작성자·서명·링크 줄을 넣지 않습니다(이슈 연결은 Refs/Closes/Fixes/Resolves). 그 줄을 지우고 다시 커밋하세요"
  fi
done < <(printf '%s\n' "$body" | git -c trailer.separators=: interpret-trailers --parse 2>/dev/null)

tool_line=$(printf '%s\n' "$body" | grep -iE '^[^[:alnum:]]*(generated|created) (with|by) ' | head -1)
if [ -n "$tool_line" ]; then
  report "생성 도구 표기 줄: $tool_line" "그 줄을 지우고 다시 커밋하세요. 메시지에는 무엇을 왜 바꿨는지만 적습니다"
fi

exit $fail
