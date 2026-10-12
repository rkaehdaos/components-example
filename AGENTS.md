# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 개요

reflectoring.io의 "컴포넌트 기반 아키텍처" 예제 프로젝트(Kotlin 2.4 + Spring Boot 3.5, Java 17, Gradle 9.8 멀티모듈). 레이어가 아니라 패키지 경계(`api` / `internal`)로 컴포넌트를 나누고, 그 경계를 ArchUnit 테스트 하나로 강제하는 것이 핵심이다.

## 명령어

JDK 17(`mise.toml`: oracle-graalvm-17)과 **Docker가 필수**다. 모든 명령은 루트에서 실행한다.

```bash
./gradlew build                      # 전체 빌드 + 테스트
./gradlew test                       # 전체 테스트
./gradlew :components:check-engine:test --tests '*JooqCheckRepositoryTest'   # 단일 테스트 클래스
./gradlew :server:test --tests '*InternalPackageTest'                        # 아키텍처 규칙 검증
./gradlew generateJooq               # Flyway 스크립트 → jOOQ 클래스 재생성
./gradlew composeDownForced          # jOOQ용 PostgreSQL 컨테이너/볼륨 제거 (Flyway checksum mismatch 시)
```

- `./gradlew bootRun`은 현재 동작하지 않는다(datasource 설정 및 `application.yml`이 없음).
- 별도 lint/formatter 설정은 없다.

## 빌드 파이프라인 (jOOQ 코드 생성)

jOOQ 생성 코드는 커밋되지 않으며, 빌드 시점에 만들어진다:

1. `common/database`의 `docker-compose.yml`이 PostgreSQL 15 컨테이너(`components-jooq`, **localhost:5431**, user/pw/db 모두 `components`)를 띄운다. `stopContainers = false`라서 빌드 후에도 계속 떠 있다.
2. `flywayMigrate`가 `common/database/src/main/resources/db/migration/` 스크립트를 적용한다.
3. `generateJooq`가 `public` 스키마를 읽어 `io.reflectoring.components.common.database` 패키지로 클래스를 생성한다.

따라서 **스키마 변경은 `common/database`에 새 Flyway 스크립트(`V00N__*.sql`)를 추가**하는 방식으로 한다. 모든 컴포넌트의 마이그레이션이 이 모듈 한곳에 모이며, 컴포넌트별 하위 디렉터리(`db/migration/check-engine/`)로 구분한다. 이미 적용된 스크립트를 수정하면 로컬 DB checksum 오류가 나므로 `composeDownForced`가 필요하다.

## 아키텍처

### 컴포넌트 규칙 (README 요약)

- 컴포넌트 = 고유 패키지. 그 아래 `api`와 `internal` 패키지를 가진다.
- `api`의 클래스는 외부에서 사용 가능, `internal`의 클래스는 해당 패키지 내부에서만 사용 가능.
- 하위 컴포넌트는 부모의 `internal` 아래에 두고, 각자 다시 `api`/`internal`을 가진다. 하위 컴포넌트끼리는 서로의 `api`만 통해 의존한다(예: `checkrunner.internal.DefaultCheckRunner` → `database.api.CheckMutations`).
- `server/src/test/.../InternalPackageTest.kt`가 `io.reflectoring.components` 아래 `.internal`로 끝나는 **모든** 패키지를 자동 탐색해 "외부에서 internal 접근 금지"를 검증한다. 새 컴포넌트도 패키지 이름 규칙만 지키면 자동으로 검사 대상이 된다. 구조를 바꾼 뒤에는 이 테스트를 돌릴 것.

### 모듈 구성

- `server` — Spring Boot 앱. 모든 컴포넌트를 의존성으로 끌어온다. ArchUnit 테스트가 여기 있다.
- `components/check-engine` — 유일한 예제 컴포넌트. 웹 페이지에 대한 임의의 체크를 비동기 실행/조회.
- `common/database` — Flyway 스크립트, jOOQ 코드 생성, `JooqAutoConfiguration`(`DSLContext` 빈).
- `common/testcontainers` — 테스트용 `@PostgreSQLTest` 메타 애노테이션. 루트 `build.gradle`이 모든 서브프로젝트에 `testImplementation`으로 추가한다.

공통 의존성 버전은 루트 `build.gradle`의 `dependencyManagement`에서 관리한다(Spring Boot BOM, AWS SDK BOM, Testcontainers BOM, jOOQ, `io.reflectoring:sqs-starter` 등).

### check-engine 내부 구조

- `api/` — 외부 계약: `CheckScheduler`(체크 예약), `CheckQueries`(결과 조회), `CheckExecutor`(실제 체크 로직 SPI) 및 값 타입들.
- `internal/queue` — `SqsCheckScheduler`가 `CheckScheduler`를 구현해 SQS로 `CheckRequest`를 발행하고, `CheckRequestListener`가 수신해 `CheckRunner`로 넘긴다. `check-engine.sqs.check-requests.*` 프로퍼티가 필요하다.
- `internal/database` — `JooqCheckRepository`가 `CheckQueries`(공개 API)와 `CheckMutations`(하위 컴포넌트 api)를 구현. check-engine 테이블에 직접 접근하는 유일한 곳이다(컴포넌트 간 DB 공유 금지 원칙).
- `internal/checkrunner` — `CheckResolver`가 컨텍스트의 모든 `CheckExecutor` 빈을 `CheckKey`로 매핑하고, `DefaultCheckRunner`가 실행 결과/에러를 DB에 기록한다. 새 체크 종류는 `CheckExecutor` 빈을 등록하면 된다.

### Spring 설정 방식

- 각 컴포넌트/하위 컴포넌트는 자신의 `@Configuration` + `@ComponentScan` 클래스를 가진다. 덕분에 트리의 어느 레벨에서든 독립 Spring 컨텍스트로 통합 테스트를 할 수 있다(예: 테스트에서 `@Import(CheckEngineDatabaseConfiguration::class)`).
- 최상위 진입점은 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 등록된 `CheckEngineAutoConfiguration` / `JooqAutoConfiguration`이다.

## 테스트

- DB가 필요한 테스트는 `@PostgreSQLTest`(Testcontainers PostgreSQL 15 + `@DataJdbcTest` + Flyway 자동 적용)를 붙이고, 필요한 하위 컴포넌트 설정을 `@Import`한다.
- 테스트 DataSource는 `SingleConnectionDataSource`라서 jOOQ와 Spring Data가 같은 커넥션을 공유한다.
- 목킹은 `mockito-kotlin`을 사용한다.

## Git 규칙

- 커밋 메시지 템플릿: `.gitmessage.txt` — `<emoji> <type>(<scope>): <subject>` 형식(제목 50자, 본문 72자 줄바꿈, 명령형·소문자 시작·마침표 없음). 제목 앞에 `.gitmessage.txt`의 type 목록에 있는 이모지를 붙인다(예: `✨ feat`, `🐛 fix`, `📝 docs`, `🔨 build`, `⬆️ upgrade`).
- `.githooks/commit-msg` (`git config core.hooksPath .githooks`로 활성화)가 브랜치명 앞의 이슈 번호(`12-add-x`, `feature/12-add-x`)를 `Refs: #12` 푸터로 자동 추가한다. 직접 넣을 필요 없다.
- PR은 `.github/PULL_REQUEST_TEMPLATE.md` 형식을 따른다.
