#!/usr/bin/env bash
# 하네스 규칙이 제대로 막고(양성), 멀쩡한 것은 통과시키는지(음성) 확인한다.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q && git config user.email t@example.com && git config user.name t && git config commit.gpgsign false
mkdir -p scripts && cp "$ROOT/scripts/check-secrets.sh" "$ROOT/scripts/check-commit-msg.sh" scripts/
fail=0
result() { if [ "$2" = "$1" ]; then echo "✓ $3"; else echo "✗ $3 (기대 $1, 실제 $2)"; fail=1; fi; }

expect() {  # expect <block|pass> <설명> <파일> <내용>  — 커밋 전 비밀·파일 검사
  mkdir -p "$(dirname "$3")"; printf '%s' "$4" > "$3"; git add -f "$3" scripts
  if scripts/check-secrets.sh --staged >/dev/null 2>&1; then got=pass; else got=block; fi
  result "$1" "$got" "$2"
  git rm -q --cached "$3"; rm -f "$3"
}
expect_msg() {  # expect_msg <block|pass> <설명> <커밋 메시지>  — 커밋 메시지 검사
  printf '%b' "$3" > msg.txt
  if scripts/check-commit-msg.sh msg.txt >/dev/null 2>&1; then got=pass; else got=block; fi
  result "$1" "$got" "$2"
}

echo "── 비밀·원본 파일"
k="sk-ant-api03-$(printf 'x%.0s' {1..30})"
expect block "Anthropic 키 차단"            a.txt "key=$k"
expect block "환경변수 실제 값 차단"          b.yml "ANTHROPIC""_API_KEY: $(printf 'a%.0s' {1..32})"   # 이 파일 자체가 걸리지 않게 나눠 씀
expect block "Voyage 키 차단"              e.txt "VOYAGE=pa-$(printf 'y%.0s' {1..32})"
expect block "원본 PDF 차단"                 plan.pdf "%PDF-1.4"
expect block ".env 차단"                     .env "X=1"
expect block "개인 편집기 설정 폴더 차단"      .idea/workspace.xml "<project/>"
expect block "루트 개인 메모 차단"            NOTES.md "메모"
expect pass  "자리표시자 통과"               docs/c.md "ANTHROPIC_API_KEY=sk-ant-... 처럼 환경변수로 넣으세요"
expect pass  "환경변수 참조 통과"            d.yml 'key: ${ANTHROPIC_API_KEY}'
expect pass  ".env.example 통과"             .env.example "VITE_API_BASE_URL=http://localhost:8080"
expect pass  "팀 설정(.github) 통과"          .github/x.yml "name: x"
expect pass  "docs 문서 통과"                docs/x.md "# 문서"

echo "── 커밋 메시지"
expect_msg block "공동 작성자 트레일러 차단"   'feat(be): 판정 추가\n\n학점 비교\n\nCo-authored-by: A <a@example.com>\n'
expect_msg block "링크 트레일러 차단"          'fix: 수정\n\nX-Session: https://example.com/s/1\n'
expect_msg block "생성 도구 표기 차단"         'docs: 정리\n\nGenerated with Some Tool\n'
expect_msg pass  "한 줄 커밋 통과"             'docs: 오타 수정\n'
expect_msg pass  "이슈 연결 트레일러 통과"     'fix(be): 판정 오류\n\n학점 비교 수정\n\nCloses: #12\n'
expect_msg pass  "본문의 '키: 값' 문장 통과"   'feat: 파서\n\nNote: merged cells\n원인: 병합 셀\n'
exit $fail
