# 현장뛰자 — 백엔드

현장실습 공고 문서(운영계획서·수기)를 AI로 구조화해서, 학생에게는 지원할 수 있는 자리와 이유·1~3지망 몰림 경고를, 현장실습지원센터에는 모집 중 현황판을 보여주는 서비스의 백엔드와 데이터 파이프라인입니다. 2026 소웨X전컴 공모전 11조 '나중에 고치조'. 프론트: [skuniv-team11/Frontend](https://github.com/skuniv-team11/Frontend)

```
src/                Spring Boot 4.1.1 · Java 21 → Render (Docker, Singapore)
pipeline/           오프라인 데이터 작업과 선행 실험(E1·E2·E5·E6, Python)
docs/               아키텍처, 결정 기록(ADR), API 계약(프론트와 공유), 협업 규칙, 하네스
scripts/            verify.sh(전체 검증), check-secrets.sh, check_pipeline.py, test-harness.sh
render.yaml         Render Blueprint (DB는 10/15 이후 따로)
docker-compose.yml  로컬 Postgres 18
```

## 처음 받은 뒤 한 번

```
git clone https://github.com/skuniv-team11/Backend.git && cd Backend
git config core.hooksPath .githooks        # 커밋 전 키·원본 문서 검사
git config user.email "<숫자>+<아이디>@users.noreply.github.com"   # GitHub Settings → Emails에 나오는 주소
```

작업 규칙은 [AGENTS.md](AGENTS.md), 브랜치·커밋 규칙은 [docs/conventions.md](docs/conventions.md), API 계약은 [docs/api/](docs/api/)에 있습니다. 작업을 마치면 `scripts/verify.sh`를 돌립니다(CI와 같은 검사).

## 로컬 실행

```
./gradlew bootRun      # Windows: gradlew.bat bootRun   → http://localhost:8080/api/ping
./gradlew test
```

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `PORT` | 8080 | Render가 10000을 넣어 줍니다 |
| `CORS_ORIGINS` | `http://localhost:5173` | 쉼표로 구분하고 패턴을 쓸 수 있습니다 |
| `ANTHROPIC_API_KEY` | — | 추천 설명, E3 실험 |

## 배포 (Render)
1. `develop`의 내용을 `main`에 올립니다: `git push origin origin/develop:refs/heads/main`
2. Render → New → **Blueprint** → 이 저장소를 고르면 `render.yaml`을 읽습니다.
3. `CORS_ORIGINS`와 `ANTHROPIC_API_KEY`를 입력합니다. 프론트 주소가 나오기 전에는 `CORS_ORIGINS`에 `http://localhost:5173`을 넣어 둡니다.
4. 첫 빌드가 끝나면 세 가지를 확인합니다.
    - 빌드 소요 시간(월 500분 예산 계산용)
    - `/actuator/health` 응답
    - Metrics의 메모리
5. `pipeline/`, `docs/` 변경은 빌드하지 않습니다(`buildFilter`).

Render Free는 15분 동안 요청이 없으면 잠들어서, 첫 요청이 1분쯤 걸릴 수 있습니다. 1차 평가가 시작되기 전에 Starter로 올립니다.

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

| 실험 | 확인한 것 | 직접 돌려야 알 수 있는 것 |
|---|---|---|
| E1 운영계획서 추출 | 스키마 제약 준수, 실행 스크립트, 채점·인용문 대조 | 실제 추출 정확도(API 키) |
| E2 수기 추출 | 48.9MB → 5.0MB 압축과 글자 판독성, 채점 로직 | 실제 추출(API 키) |
| E3 Java SDK | **합격**(10/1). 호출 2번으로 full 필드 추출 성공, 파이썬 E1과 겹치는 15항목 중 14개 값·쪽 일치 | — |
| E4 배포 | 테스트, 로컬 실행·CORS·메모리(RSS 약 213MB), 브라우저로 프론트→백엔드 호출 | Docker 빌드·Render 실제 배포 |
| E5 임베딩 | 병합 셀 파싱, Hit@K 계산(합성 데이터) | Voyage 한국어 품질 |
| E6 외부 데이터 | **합격**(10/1). 국세청·NCS 적재. ODsay는 호출만 확인하고 시드에는 쓰지 않음(ADR-0002 개정) | 통근은 카카오 실시간(ADR-0007) — 키 발급 후 첫 호출 |

실행 방법은 [pipeline/README.md](pipeline/README.md)에 있습니다.

## DB (10/15 이후)
- **로컬:** `docker compose up -d`로 Postgres 18을 띄웁니다.
- **Render:** 무료 Postgres는 10/15 이후 대시보드에서 만듭니다. Internal Database URL(`postgresql://USER:PASSWORD@HOST:PORT/DB`)을 JDBC 형식으로 바꿔 환경변수 3개를 넣습니다.
    - `DB_URL=jdbc:postgresql://HOST:PORT/DB`
    - `DB_USER`
    - `DB_PASSWORD`

## 저장소 규칙
- **올리지 않는 것**: 원본 PDF·엑셀, API 키, 실험 결과. 모두 `.gitignore`에 들어 있습니다.
- **키를 커밋했다면**: 이력을 지워도 노출된 것으로 보고 즉시 폐기하고 재발급합니다.
