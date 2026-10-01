# ADR-0006 커밋 작성자와 메시지

- 상태: 확정 (2026-09-30)
- 결정
  - 커밋 작성자는 **실제로 작업한 팀원 한 명**이고, GitHub 계정에 연결(인증)된 이메일을 쓴다.
  - 메시지 끝에 공동 작성자·서명·링크 트레일러(`Co-authored-by:`, `X-Session: URL` 등)와 생성 도구 표기 줄(`Generated with …`)을 넣지 않는다. 이슈 연결(`Refs/Closes/Fixes/Resolves`)만 쓴다.
  - 루트의 숨김 파일·폴더와 루트 문서는 허용 목록만 올린다. 개인 편집기·도구 설정과 개인 메모는 `.git/info/exclude`로 각자 뺀다.
  - `develop`은 PR + squash merge만 쓰고, squash 기본 메시지는 **PR 제목**으로 둔다(PR 본문이 커밋에 섞이지 않게). `main`은 `develop`에서 fast-forward로만 올린다(10/1 브랜치 모델 변경, `docs/conventions.md`).
- 이유
  - 공모전 평가에서 저장소 기여 내역이 곧 팀원별 역할의 근거다. 연결 안 된 이메일로 커밋하면 기여로 잡히지 않고, 팀원이 아닌 계정이 기여자로 올라가면 설명이 필요해진다.
  - 코딩 도구가 기본값으로 붙이는 공동 작성자·링크 줄은 사람이 매번 지우기 어렵다. 코드로 막는다.
- 장치
  - 로컬: `.githooks/commit-msg` → `scripts/check-commit-msg.sh`, `.githooks/pre-commit` → `scripts/check-secrets.sh`(숨김 파일·루트 문서)
  - CI: `commits` job → `scripts/check-commits.sh`. PR의 모든 커밋에 대해 메시지 규칙 + 작성자 계정이 `.github/authors`에 있는지 확인
  - 저장소 설정: squash만 허용, 기본 메시지 PR 제목, 병합 뒤 브랜치 자동 삭제. 룰셋 `develop 보호`(PR 필수·squash만·`ci-ok` 필수·강제 push·삭제 금지), `main 보호(배포)`(강제 push·삭제 금지)
- 대가: 짝 작업도 커밋은 한 사람 이름으로 남는다 → PR 본문에 함께한 사람을 적는다. 새 팀원은 `.github/authors`에 아이디를 추가하는 PR부터 올린다.
- 다시 볼 때: 공모전이 끝나고 저장소를 포트폴리오로 넘길 때.
