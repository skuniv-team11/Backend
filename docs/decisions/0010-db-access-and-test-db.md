# ADR-0010 스키마는 Flyway, DB 접근은 JdbcClient, 테스트 DB는 Testcontainers

- 상태: 확정 (2026-10-01)
- 결정
  - 스키마는 Flyway로 관리한다. 앱이 뜰 때 `src/main/resources/db/migration/`의 `V1__init.sql`부터 순서대로 적용한다. 적용한 파일은 고치지 않고 다음 번호(`V2__*.sql`)를 새로 만든다.
  - DB 접근은 Spring `JdbcClient`(`spring-boot-starter-jdbc`)로 SQL을 직접 쓴다. JPA는 쓰지 않는다.
  - 테스트는 Testcontainers로 Postgres 18 컨테이너를 띄워 돈다(`src/test/.../TestcontainersConfiguration`). H2는 쓰지 않는다.
  - 연결 정보는 환경변수 `DB_URL`·`DB_USER`·`DB_PASSWORD`. 기본값은 로컬 `docker compose`(Postgres 18, 로컬 전용 계정)다.
- 이유
  - V1이 Postgres 전용 기능(배열·`num_nonnulls`·부분 유니크 인덱스)을 써서 H2로는 돌지 않는다. 배포(Render, 18)와 같은 메이저 버전으로 테스트해야 V1의 CHECK·인덱스가 실제로 검증된다.
  - `JdbcClient`는 V1 SQL을 그대로 쓰고 배열 컬럼도 SQL로 다룬다. 시드 테이블은 읽기가 대부분이라 엔티티 매핑으로 얻는 게 적다.
  - CI(GitHub Actions `ubuntu-latest`)에는 Docker가 있어 설정 없이 같은 테스트가 돈다.
- 결과
  - `./gradlew test`에 Docker가 필요하다(CI에는 있다). 첫 실행은 `postgres:18` 이미지를 받느라 느리다.
  - DB가 없으면 앱이 뜨지 않는다. 10/2부터 로컬에서 서버를 띄우지 않고 Render DB만 쓴다(ADR-0005 개정).
  - **Render DB 없이 `main`을 배포하면 실패한다.** DB를 만들고 `DB_URL`·`DB_USER`·`DB_PASSWORD`를 넣은 뒤에 배포한다(ADR-0005, 10/2 개정으로 DB를 지금 만든다).
  - 검증(10/1): 테스트 13개 통과(Flyway가 Postgres 18.6에 V1 적용, 테이블 21개), `docker compose` + `bootRun`에서 `/actuator/health` UP.
- 다시 볼 때: 같은 매핑 코드가 반복돼 부담이 될 때(Spring Data JDBC 검토), 또는 CI 테스트 시간이 문제가 될 때.
