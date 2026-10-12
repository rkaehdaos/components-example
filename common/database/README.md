# Database

이 모듈에는 Flyway가 관리하는 [데이터베이스 마이그레이션 스크립트](src/main/resources/db/migration)가 들어 있으며, JOOQ 라이브러리에서 사용할 Java 클래스를 데이터베이스로부터 생성하는 역할을 합니다.

이 모듈의 SQL 스크립트를 변경한 뒤에는 프로젝트 루트 폴더에서 `./gradlew generateJooq`를 실행하세요. 그러면 이 스크립트로 Flyway가 초기화한 임시 데이터베이스로부터 JOOQ 클래스가 생성됩니다.

`generateJooq` 태스크는 로컬 PostgreSQL 데이터베이스를 띄우고, 거기에 Flyway 스크립트를 실행해 스키마를 갱신한 뒤, JOOQ의 소스 코드 생성기로 데이터베이스 모델로부터 Java 클래스를 생성합니다. 이렇게 하면 데이터베이스의 모델과 코드의 모델이 항상 동기화된 상태로 유지됩니다.

"Migration checksum mismatch for migration" 같은 Flyway 오류가 발생하면, 프로젝트 루트 폴더에서 `./gradlew composeDownForced` 명령으로 로컬 PostgreSQL 데이터베이스를 초기화할 수 있습니다.
