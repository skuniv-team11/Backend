#!/usr/bin/env bash
# 범위 안의 커밋을 검사한다. CI의 commits job이 쓰고, 로컬에서도 돌릴 수 있다.
#   scripts/check-commits.sh <base> <head>     예: scripts/check-commits.sh origin/develop HEAD
#   base가 비었거나 0000…(첫 push)이면 head까지 전부 본다.
# 검사
#   1) 메시지 규칙(scripts/check-commit-msg.sh)
#   2) 작성자 이메일이 GitHub 계정에 연결돼 있고 그 계정이 .github/authors 에 있는지
#      — GH_TOKEN과 GITHUB_REPOSITORY가 있을 때만(CI에서는 항상 켜짐)
# 이유: 기여 내역은 팀원 계정으로만 남는다(ADR-0006). 연결 안 된 이메일로 커밋하면 기여로 잡히지 않는다.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
base="${1:-}"; head="${2:-HEAD}"
if [ -z "$base" ] || [ -z "${base//0/}" ]; then range="$head"; else range="$base..$head"; fi
if ! git rev-parse -q --verify "$head^{commit}" >/dev/null; then echo "✗ 커밋을 찾지 못했습니다: $head"; exit 1; fi
mapfile -t shas < <(git rev-list --no-merges "$range" 2>/dev/null)
if [ ${#shas[@]} -eq 0 ]; then echo "✓ 검사할 커밋 없음"; exit 0; fi

allow=$(grep -vE '^[[:space:]]*(#|$)' .github/authors 2>/dev/null | sed 's/[[:space:]]//g' | tr '[:upper:]' '[:lower:]')
api=false
if [ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then api=true; fi

fail=0
msg=$(mktemp); trap 'rm -f "$msg"' EXIT
for sha in "${shas[@]}"; do
  tag="[${sha:0:7}] $(git log -1 --format=%s "$sha")"

  # 웹에서 squash merge 한 커밋은 PR 단계에서 이미 검사했다(GitHub가 PR 커밋 작성자를 공동 작성자 줄로 붙인다)
  if [ "$(git log -1 --format=%ce "$sha")" != "noreply@github.com" ]; then
    git log -1 --format=%B "$sha" > "$msg"
    if ! out=$(scripts/check-commit-msg.sh "$msg"); then echo "$tag"; echo "$out"; fail=1; fi
  fi

  if $api; then
    email=$(git log -1 --format=%ae "$sha")
    if ! login=$(gh api "repos/$GITHUB_REPOSITORY/commits/$sha" --jq '.author.login // ""' 2>/dev/null); then
      echo "✗ $tag: GitHub API로 작성자를 확인하지 못했습니다(잠시 뒤 다시 실행)"; fail=1; continue
    fi
    if [ -z "$login" ]; then
      echo "✗ $tag: 작성자 이메일 $email 이 GitHub 계정에 연결돼 있지 않습니다"
      echo "  → git config user.email 을 GitHub Settings → Emails에 나오는 …@users.noreply.github.com 주소로 바꾸고 git commit --amend --reset-author 로 다시 작성하세요(또는 이 주소를 GitHub에서 인증)"
      fail=1
    elif ! printf '%s\n' "$allow" | grep -qxF "$(printf '%s' "$login" | tr '[:upper:]' '[:lower:]')"; then
      echo "✗ $tag: 작성자 @$login 이 .github/authors 에 없습니다"
      echo "  → 팀원이면 .github/authors 에 GitHub 아이디를 한 줄 추가하세요. 팀원 계정이 아니면 팀원 이름으로 커밋을 다시 작성합니다"
      fail=1
    fi
  fi
done

if [ $fail -eq 0 ]; then
  if $api; then echo "✓ 커밋 ${#shas[@]}개: 메시지·작성자 검사 통과"; else echo "✓ 커밋 ${#shas[@]}개: 메시지 검사 통과(작성자 계정 확인은 CI에서)"; fi
fi
exit $fail
