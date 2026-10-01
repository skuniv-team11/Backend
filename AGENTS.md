# AGENTS.md — 현장뛰자 백엔드

이 파일은 이 저장소에서 작업하는 사람과 코딩 도구가 **처음 읽는 목차**다. 규칙 전체가 아니라 어디를 봐야 하는지를 적는다.

## 무엇을 만드나
현장실습 공고 문서(운영계획서 PDF·참여수기)를 AI로 구조화해, 학생에게는 지원할 수 있는 자리와 이유(**원문 근거** 포함)·1~3지망 몰림 경고를, 현장실습지원센터에는 모집 중 현황판을 보여주는 서비스의 백엔드와 데이터 파이프라인. 범위는 대학일자리플러스본부가 섭외하는 표준 현장실습학기제(ICT 학점연계 인턴십은 MVP 제외). 프론트는 `skuniv-team11/Frontend`. 제출 11/1, 1차 평가 11/2~11/9(공개 링크), 최종 발표 11/12.

## 저장소 지도
| 경로 | 내용 |
|---|---|
| `src/` | Spring Boot 4.1 · Java 21. 패키지 루트 `kr.ac.skuniv.coopradar` (`auth/` 로그인·체험 계정, `me/` 내 정보, `common/` 오류 코드·시간, `web/` 공통 컨트롤러, `config/` 설정, `spike/` 추출 실험) |
| `src/main/resources/prompts/` | LLM 시스템 프롬프트(코드와 같이 버전 관리) |
| `pipeline/` | 오프라인 작업(문서 추출·외부 데이터 적재·실험), Python → `pipeline/AGENTS.md` |
| `docs/` | 아키텍처, 결정 기록(ADR), **API 계약(`docs/api/`, 프론트와 공유)**, 협업 규칙, 하네스 설명 |
| `scripts/` | `verify.sh`(전체 검증), 비밀·원본 파일 검사, 스키마 검사, 하네스 자체 테스트 |
| `.github/` | CI, PR·이슈 템플릿, CODEOWNERS |

## 작업이 끝났다고 말하기 전에
```
scripts/verify.sh          # 바뀐 부분만. 전체는 scripts/verify.sh --all
```
실패 메시지에 고치는 방법이 적혀 있다. 통과하지 못하면 끝난 것이 아니다. CI도 같은 검사를 돈다.

## 절대 하지 말 것 (Never)
- 원본 데이터(`*.pdf`, `*.xlsx`, `*.hwp`, `data/raw/`), API 키, `.env`를 커밋하지 않는다. 공개 저장소다(ADR-0004).
- 학생 프로필(평점·학과·사는 곳 등)은 [저장]+동의했을 때만 DB에 두고 탈퇴하면 바로 지운다. 로그에는 남기지 않는다(ADR-0008).
- 매칭 결과의 이름·전화번호, 학과×직무 매칭 집계를 저장소·DB에 넣지 않는다. 직무별 배정 수만 쓴다.
- 생성형 AI 출력을 검증 없이 응답에 내보내지 않는다(스키마 검증 → 근거 인용 확인 → 사람 확인한 시드).
- 서비스 실행 중에 국세청·NCS 등 공공 API를 부르지 않는다. `pipeline/`에서 미리 계산한다(ADR-0002). 실행 중 외부 호출은 Claude·임베딩·카카오 대중교통(통근)뿐이다.
- 카카오 API 결과(통근 시간·경로·좌표)를 DB·캐시·시드에 저장하지 않는다. 부를 때마다 쓰고 버린다(ADR-0007).
- 커밋에 공동 작성자·링크 트레일러나 생성 도구 표기 줄을 넣지 않는다. 작성자는 GitHub 계정에 연결된 본인 이메일 한 명. 개인 도구 설정·메모는 커밋하지 않는다(ADR-0006, `commit-msg` 훅과 CI `commits`가 막는다).

## 먼저 물어볼 것 (Ask first)
- 새 의존성, 빌드 도구·런타임 버전 변경
- API 응답 형태 변경 → `docs/api/`를 먼저 고치고 프론트와 합의(프론트가 목업으로 작업 중)
- `render.yaml`, `Dockerfile`, `.github/workflows/` 변경
- DB 스키마(Flyway 마이그레이션) 추가·변경

## 항상 할 것 (Always)
- 커밋·PR·주석은 한국어. 커밋은 `feat|fix|docs|refactor|test|chore(범위): 요약` (`docs/conventions.md`).
- 새 엔드포인트는 `/api/**` 아래에 두고 MockMvc 테스트(정상 1 + 오류 또는 CORS 1)와 `docs/api/` 예시 JSON을 같은 PR에 넣는다. `docs/api/`를 고치면 `python scripts/build_openapi.py`로 Swagger 계약 스펙을 다시 만들어 같이 넣는다(ADR-0011).
- `/api/**`는 기본이 로그인 필요다. 로그인 없이 부르는 API에만 `@PublicApi`, 역할 제한은 `@RequireRole`을 붙이고 사용자는 `AuthUser` 인자로 받는다. 권한은 `docs/api` 목록의 권한과 같아야 한다(`ContractTest`가 대조, ADR-0012). 오류는 `ApiException(ErrorCode, 메시지)`로 던진다.
- 결정이 생기면 `docs/decisions/`에 ADR 한 장(무엇을, 왜, 언제 다시 볼지).
- 외부 조건(요금·한도·버전)은 추측하지 말고 공식 문서를 확인해 출처를 남긴다.

## 알아두면 막히지 않는 것
- 명령: `./gradlew test`(필수, **Docker 필요** — 테스트가 Postgres 18 컨테이너를 띄운다) · `docker compose up -d` 뒤 `./gradlew bootRun`(:8080, `/api/ping`, `/actuator/health`, `/swagger-ui.html`) · `./gradlew bootJar`
- Render 512MB: JVM 옵션은 `Dockerfile`의 `JAVA_TOOL_OPTIONS`. 요청마다 큰 PDF를 메모리에 올리지 않는다.
- Render 빌드 월 500분 → `main` 브랜치만 배포(개발은 `develop`). `pipeline/`·`docs/` 변경은 빌드하지 않는다(`render.yaml` buildFilter).
- 구조화 출력용 Java enum은 **한글 상수명**, `@JsonProperty` 금지 → `StructuredOutputRulesTest`(ADR-0003).
- Boot 4는 Jackson 3(`tools.jackson`), Anthropic SDK는 Jackson 2. SDK 모델 직렬화는 `com.anthropic.core.ObjectMappers.jsonMapper()`.
- DB 접근은 `JdbcClient`로 SQL을 직접 쓴다(JPA 없음). 스키마 변경은 새 `V2__*.sql`로만, 적용한 파일은 고치지 않는다(ADR-0010). 새 `@SpringBootTest`에는 `@Import(TestcontainersConfiguration.class)`.
- 배포 DB는 10/15 이후 Render 무료 Postgres(ADR-0005). `postgresql://` URL을 `jdbc:postgresql://host:port/db`로 바꿔 `DB_URL`에. **DB 없이는 앱이 뜨지 않으므로 그 전에 `develop`을 `main`에 올리지 않는다.**
- 로컬에서 `JWT_SECRET`을 비우면 서버가 뜰 때마다 임시 키를 만든다(다시 뜨면 로그인이 풀림). 배포에는 반드시 넣는다.
- 응답 테스트는 `Contract.assertSameShape(응답, Contract.responseExample(operationId, 상태, null))`로 계약 예시와 모양을 맞춘다.
- 한글 경로 `InvalidPathException` → `-Dsun.jnu.encoding=UTF-8`.
- 모집기간 지원 신호는 **가상 데이터**다. 응답에 가상 여부 필드를 빼지 않는다.
