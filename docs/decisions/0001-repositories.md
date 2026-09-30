# ADR-0001 저장소 구성: 백엔드(+파이프라인)와 프론트 분리

- 상태: 확정 (2026-09-30)
- 결정: GitHub 조직 `skuniv-team11` 아래 공개 저장소 2개.
  - `Backend`: Spring Boot, `pipeline/`(오프라인 추출·외부 데이터·실험), `docs/`(API 계약 포함)
  - `Frontend`: React
  - 이름은 팀 조직(`skuniv-team11`) 안에서 역할만 드러나게 짧게 쓴다(팀 결정). 내부 빌드 이름(`settings.gradle`, `package.json`)은 `coop-radar-backend`/`coop-radar-frontend`를 유지한다(npm 이름은 소문자만 허용).
- 이유: 백엔드와 프론트는 담당자·배포처(Render/Vercel)·빌드 도구가 달라 저장소를 나누는 쪽이 팀에 익숙하다. 파이프라인은 백엔드 담당이 혼자 맡고 결과(시드)가 백엔드로 들어가므로 백엔드 저장소에 둔다.
- 대가: API 계약이 한 저장소에만 있어 프론트는 링크로 참조한다 → API 변경 PR은 프론트 저장소에 이슈로 알린다.
- 다시 볼 때: 파이프라인을 다른 사람이 맡거나 배포 주기가 달라질 때(별도 저장소 분리).
