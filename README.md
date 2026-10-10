# 현장뛰자 — 백엔드

현장실습 공고 문서(운영계획서·수기)를 AI로 구조화해서, 학생에게는 지원할 수 있는 자리와 경험에 맞는 자리·현장실습 진행·실습 뒤 커리어를, 현장실습지원센터에는 현황판과 접수·매칭·선발·마무리를 보여주는 서비스의 백엔드와 데이터 파이프라인입니다. 2026 소웨X전컴 공모전 11조 '나중에 고치조'. 프론트: [skuniv-team11/Frontend](https://github.com/skuniv-team11/Frontend)

```
src/                Spring Boot 4.1.1 · Java 21 → Render (Docker, Singapore)
pipeline/           오프라인 데이터 작업과 선행 실험(E1·E2·E5·E6, Python)
docs/               아키텍처, 결정 기록(ADR), API 계약(프론트와 공유), 협업 규칙, 하네스
scripts/            verify.sh(전체 검증), check-secrets.sh, check_pipeline.py, test-harness.sh
render.yaml         Render Blueprint (DB는 대시보드에서 따로, ADR-0005)
```

## 처음 받은 뒤 한 번

```
git clone https://github.com/skuniv-team11/Backend.git && cd Backend
git config core.hooksPath .githooks        # 커밋 전 키·원본 문서 검사
git config user.email "<숫자>+<아이디>@users.noreply.github.com"   # GitHub Settings → Emails에 나오는 주소
```

작업 규칙은 [AGENTS.md](AGENTS.md), 브랜치·커밋 규칙은 [docs/conventions.md](docs/conventions.md), API 계약은 [docs/api/](docs/api/)에 있습니다. 작업을 마치면 `scripts/verify.sh`를 돌립니다(CI와 같은 검사).

## 서버 주소

로컬에서 서버를 띄우지 않습니다(10/2). 배포 서버 하나만 씁니다.
- API: https://coop-radar-api.onrender.com (`/api/ping`, `/actuator/health`)
- API 문서(Swagger): https://coop-radar-api.onrender.com/swagger-ui.html — 드롭다운 '계약'은 `docs/api` 36개 전부, '구현'은 지금 서버에 있는 것만 보입니다(ADR-0011).
- 테스트는 PR을 올리면 CI가 돌립니다(`./gradlew test`가 Testcontainers로 Postgres 18을 띄움).

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `PORT` | 8080 | Render가 10000을 넣어 줍니다 |
| `CORS_ORIGINS` | `http://localhost:5173` | 쉼표로 구분하고 패턴을 쓸 수 있습니다 |
| `ANTHROPIC_API_KEY` | — | 추천 설명, E3 실험 |
| `JWT_SECRET` | — | 로그인 토큰 서명 키(32바이트 이상 무작위 값). Render는 Blueprint가 만들 때 무작위로 넣는다. 비어 있으면 서버가 뜰 때마다 임시 키(다시 뜨면 로그인이 풀림) |
| `CLIENT_IP_HEADER` | `CF-Connecting-IP` | 체험 계정 호출 제한에 쓰는 IP 헤더(Render 앞단 Cloudflare, ADR-0013) |
| `GUEST_PER_IP_PER_HOUR` | `300` | 체험 계정 만들기 IP당 1시간 한도. 0이면 제한 없음. Render 대시보드에서 바꾸면 다시 배포하지 않아도 된다 |
| `KAKAO_REST_API_KEY` | — | 통근 조회(카카오 주소 검색 + 대중교통) |
| `REPLAY_DEFAULT_AS_OF` | `2026-07-23` | 시연 기준일(`/api/rounds/current`의 `replay.defaultAsOf`). 모집기간 밖이면 가까운 끝 날짜로 맞춘다 |
| `DB_URL` · `DB_USER` · `DB_PASSWORD` | — | Render에 넣습니다(아래 'DB'). 없으면 앱이 뜨지 않습니다 |

## 배포 (Render)
웹 서비스는 Starter(`0.5c-512mb`), DB는 무료 Postgres입니다(ADR-0005). 순서가 중요합니다 — DB 없이는 앱이 뜨지 않습니다.

1. **DB:** Render → New → Postgres. 이름 `coop-radar-db`, Region **Singapore**(웹 서비스와 같아야 Internal URL이 됨), Version 18, Plan Free. 아래 'DB'처럼 연결값 3개를 만들어 둡니다.
2. **main 올리기:** `git push origin origin/develop:refs/heads/main`(fast-forward).
3. **Blueprint:** Render → New → **Blueprint** → 이 저장소, **Branch `main`**을 고르면 `render.yaml`을 읽습니다. 묻는 값을 넣습니다.
    - `CORS_ORIGINS`: `https://fieldrun.pages.dev,https://*.fieldrun.pages.dev`(프론트 Cloudflare Pages 운영 + 미리보기. 미리보기 주소는 `<해시>.fieldrun.pages.dev`·`<브랜치>.fieldrun.pages.dev` — [Cloudflare 문서](https://developers.cloudflare.com/pages/configuration/preview-deployments/))
    - `ANTHROPIC_API_KEY`, `KAKAO_REST_API_KEY`
    - `DB_URL`·`DB_USER`·`DB_PASSWORD`(1번에서 만든 값)
    - `JWT_SECRET`은 묻지 않습니다. Render가 무작위 값을 만듭니다.
4. 첫 배포가 끝나면 확인합니다.
    - `https://<서비스>.onrender.com/actuator/health` → `UP`, `/api/ping`
    - 빌드 소요 시간(월 500분 예산 계산용), Metrics의 메모리(512MB 안)
5. `pipeline/`, `docs/` 변경은 빌드하지 않습니다(`buildFilter`). `sync: false` 값은 Blueprint를 처음 만들 때만 묻습니다. 그 뒤로는 대시보드 Environment에서 고칩니다.

## E3: 운영계획서 추출 (Java SDK)
```
./gradlew bootJar
ANTHROPIC_API_KEY=... java -jar build/libs/coop-radar-backend-0.0.1.jar --spring.profiles.active=spike "<운영계획서.pdf>"
```
- **결과:** `build/spike-out/*.json`에 저장됩니다(`{institution, jobs, inconsistencies}` — 파이썬 파이프라인과 같은 모양).
- **호출 2번:** PDF 1건마다 `OperationPlanInstitutionPart`(기관+불일치)와 `OperationPlanJobsPart`(직무)를 따로 부릅니다. 셋을 한 스키마에 담으면 문법 크기 한도를 넘습니다([ADR-0003](docs/decisions/0003-structured-output-schema.md)).
- **추출 형식:** 위 두 레코드입니다. SDK가 레코드에서 JSON 스키마를 만들고, 반복되는 `SourcedText`를 `$defs`로 묶습니다.
- **한글 경로 오류:** 경로에 한글이 있어서 오류가 나면 `-Dsun.jnu.encoding=UTF-8`을 붙입니다.

## 선행 실험 현황 (10/1~10/3)

| 실험 | 결과 | 남은 것 |
|---|---|---|
| E1 운영계획서 추출 | **합격**(9/30, #3). 5건 144/145 = 99.3%(기준 90%), 근거 쪽 번호 193/193(텍스트 쪽). **18건 본 추출 완료**(10/1, #20 호출 2번 — 직무 40개, 약 $2.42) → 시드(#21, ADR-0014) | — |
| E2 수기 추출 | **합격**(10/1, #6). 39건, 사람 판정 117/117, 2026-2 참여기관에 연결되는 수기 17건 | — |
| E3 Java SDK | **합격**(10/1, #7). 호출 2번으로 full 필드 추출 성공, 파이썬 E1과 겹치는 15항목 중 14개 값·쪽 일치 | — |
| E4 배포 | **백엔드 배포 완료**(10/2). Render Starter·싱가포르 — 빌드 약 2분 30초 · 기동 27.8초 · 메모리 276MB/512MB · CORS(운영·미리보기 허용, 다른 출처 403) · 프론트는 Cloudflare Pages https://fieldrun.pages.dev 에서 이 API를 부른다(10/4 확인) | — |
| E5 임베딩 | **불합격 → 임베딩 쓰지 않음**(10/1). 학과명 질의 Hit@5: 무작위 29.4% · 키워드 35.3% · Voyage 47.1% · 선호 전공 규칙 52.0%(근사). 추천은 규칙 + 키워드(ADR-0018) | — |
| E6 외부 데이터 | **합격**(10/1). 국세청·NCS 적재. ODsay는 호출만 확인하고 시드에는 쓰지 않음(ADR-0002 개정). 통근은 카카오 실시간(ADR-0007, 10/2 배포 서버에서 확인) | — |

실행 방법은 [pipeline/README.md](pipeline/README.md)에 있습니다.

## DB
- **스키마·접근:** 스키마는 Flyway(`src/main/resources/db/migration/`), 접근은 `JdbcClient`입니다(ADR-0010). 테스트는 CI가 Testcontainers로 Postgres 18을 띄워 돌립니다.
- **시드:** 앱이 뜰 때 Flyway가 `R__seed.sql`(2026-2 실제 자료: 18기관·40직무·학과 60·근거·수기 17·NCS)을 넣습니다. 바꾸면 다음 기동 때 다시 적용됩니다. 만드는 법은 [pipeline/seed/README.md](pipeline/seed/README.md)(ADR-0014).
- **Render:** 무료 Postgres(singapore, 18). DB 페이지 Connections의 **Internal Database URL** `postgresql://USER:PASSWORD@HOST/DB`를 나눠 환경변수 3개에 넣습니다.
    - `DB_URL=jdbc:postgresql://HOST/DB` (`@` 뒤 부분 앞에 `jdbc:postgresql://`. 포트가 없으면 5432)
    - `DB_USER=USER`
    - `DB_PASSWORD=PASSWORD`
- **무료 DB 만료:** 만든 날 + 30일에 만료됩니다. 10/2에 만든 DB는 11/1(제출일)에 만료되므로 **10/15~10/31 사이에 지우고 새로 만듭니다**(10/15에 만들면 11/14 만료, ADR-0005). 무료 DB는 워크스페이스당 1개라 먼저 지웁니다.
    1. 기존 DB 삭제 → 같은 설정으로 새로 만들기
    2. 웹 서비스 Environment에서 `DB_URL`·`DB_USER`·`DB_PASSWORD`를 새 값으로 바꾸고 Save(다시 배포됨)
    3. 앱이 뜰 때 Flyway가 스키마와 시드(`R__seed.sql`)를 다시 넣습니다. 예시 프로필은 `application.yml`이라 그대로입니다. 사라지는 건 가입 계정·저장한 프로필·담은 지망뿐입니다.

## 저장소 규칙
- **올리지 않는 것**: 원본 PDF·엑셀, API 키, 실험 결과. 모두 `.gitignore`에 들어 있습니다.
- **키를 커밋했다면**: 이력을 지워도 노출된 것으로 보고 즉시 폐기하고 재발급합니다.
