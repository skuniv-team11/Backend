# 협업 규칙

백엔드(이 저장소)와 프론트(`skuniv-team11/Frontend`) 저장소가 같은 규칙을 씁니다.

## 처음 받은 뒤 한 번
```
git config core.hooksPath .githooks    # 커밋 전 비밀·원본 파일, 커밋 메시지 검사
git config user.email "<숫자>+<아이디>@users.noreply.github.com"   # GitHub Settings → Emails에 나오는 주소
```

## 브랜치
| 브랜치 | 용도 | 규칙 |
|---|---|---|
| `develop` | 개발 기준(기본 브랜치) | 직접 push 금지. PR + CI(`ci-ok`) 통과 후 squash merge(커밋 메시지는 PR 제목) |
| `main` | 배포(Render·Vercel) | `develop`에서 fast-forward push로만 올린다: `git push origin origin/develop:refs/heads/main`. PR로 올리면 squash 때문에 갈라진다. 빌드 시간(월 500분) 때문에 하루 1~2번 |
| `feat/<영역>-<주제>` | 작업 | 예: `feat/be-eligibility`, `feat/fe-profile`, `fix/fe-cors` |

## 커밋
`<종류>(<범위>): <요약>` — 종류 `feat fix docs refactor test chore`, 범위 `be fe pipeline docs ci`
예: `feat(be): 자격 판정 규칙 엔진 추가`, `fix(fe): 추천 카드 근거 쪽 번호 표시`
- 작성자는 **GitHub 계정에 연결된 본인 이메일**(`git config user.email`). GitHub가 주는 `…@users.noreply.github.com` 주소를 권장합니다(항상 계정에 연결되고, 개인 메일이 공개 이력에 남지 않음). 인증 안 된 주소로 커밋하면 기여로 잡히지 않고 CI `commits`가 실패합니다.
- 메시지 끝에는 이슈 연결(`Closes: #12`)만 씁니다. 공동 작성자·링크 줄, 생성 도구 표기 줄은 `commit-msg` 훅이 막습니다. 함께 작업한 사람은 PR 본문에 적습니다.
- 새 팀원은 `.github/authors`에 자기 GitHub 아이디를 추가하는 PR부터 올립니다.
- 코딩 도구를 쓴다면 커밋·PR에 도구 표기를 붙이지 않도록 **도구 설정에서 끕니다**(개인 설정 파일은 커밋하지 않음).

## PR
- 작게(파일 10개 안쪽 권장). 템플릿의 확인 항목을 채운다.
- API 형태가 바뀌면 이 저장소 `docs/api/`를 같은 PR에서 고치고, 프론트 저장소에 이슈로 알린다(프론트가 목업으로 먼저 작업).
- 리뷰어는 CODEOWNERS로 자동 지정된다.

## 환경변수
| 이름 | 어디 | 비고 |
|---|---|---|
| `CORS_ORIGINS` | Render | Vercel 운영·미리보기 주소 패턴 |
| `ANTHROPIC_API_KEY` | Render, 로컬 | 절대 커밋·로그 금지 |
| `KAKAO_REST_API_KEY` | Render, 로컬 | 카카오 디벨로퍼스 REST API 키. 통근 조회(ADR-0007). 절대 커밋·로그 금지 |
| `VITE_API_BASE_URL` | Vercel, 프론트 `.env.local` | 브라우저에 노출됨. 주소만 |
| `VOYAGE_API_KEY`, `NTS_SERVICE_KEY`, `NCS_SERVICE_KEY`, `JUSO_COORD_API_KEY` | 로컬(`pipeline/`) | 공공데이터포털은 Decoding 키. `JUSO_COORD_API_KEY`는 도로명주소 좌표제공 API 승인키. `ODSAY_API_KEY`는 더 쓰지 않는다(ADR-0002 개정) |
