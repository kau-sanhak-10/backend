# backend

산학프로젝트 10조 **일로** 백엔드 API 서버 (Spring Boot)

## 기술 스택

- Java 21
- Spring Boot 4.1
- Spring Web MVC · Validation
- Spring Data JPA (Hibernate)
- PostgreSQL 17 (로컬은 Docker Compose로 실행)
- Flyway (DB 마이그레이션)
- Spring Boot Actuator (서버 상태 확인)
- Springdoc OpenAPI (Swagger UI)
- Lombok

> Redis · CI/CD는 이후 PR에서 추가되며, 추가될 때 이 문서도 같이 갱신합니다.

## 필요 환경

- **JDK 21**
  - IntelliJ: `File → Project Structure → Project SDK`를 21로 설정
- **Docker Desktop**
  - 로컬 DB(PostgreSQL)를 띄울 때 필요합니다. 설치 후 앱을 켜 두세요 (작업표시줄 고래 아이콘, `Engine running`)

## 처음 실행할 때 (요약)

1. `.env.example`을 복사해 `.env` 만들기 → [환경변수 설정](#환경변수-설정)
2. Docker Desktop 켜기
3. `docker compose up -d` → [로컬 DB 실행](#로컬-db-실행-docker)
4. 서버 실행 → [실행 방법](#실행-방법)
5. http://localhost:8080/actuator/health 에서 `"status":"UP"` 확인

## 환경변수 설정

프로젝트 루트에서 `.env.example`을 복사해 `.env` 파일을 만듭니다.

```bash
cp .env.example .env
```

- `.env`에는 API 키 같은 **비밀값**이 들어가므로 **Git에 올리지 않습니다** (`.gitignore`에 등록되어 있음)
- 비밀값은 노션·디스코드 공개 채널에 붙여넣지 말고, 담당자에게 개인적으로 받습니다
- `.env`가 없어도 서버는 기본값으로 실행됩니다

| 변수 | 설명 | 기본값 |
| --- | --- | --- |
| `SERVER_PORT` | 서버 포트 | `8080` |
| `DB_URL` | 로컬 DB 주소 | `jdbc:postgresql://localhost:5432/ilro` |
| `DB_USERNAME` | 로컬 DB 사용자 | `ilro` |
| `DB_PASSWORD` | 로컬 DB 비밀번호 | `ilro` |
| `CORS_ALLOWED_ORIGINS` | API를 호출할 수 있는 프론트 주소 (쉼표로 구분) | `http://localhost:3000,http://localhost:5173` |
| `KAKAO_REST_API_KEY` | 카카오 REST API 키 (주소 → 좌표, 이동시간 조회) | 없음 · 팀원한테 요청 |

- `DB_*` 기본값은 `docker-compose.yml`과 같아서 보통은 바꿀 필요가 없습니다

## 로컬 DB 실행 (Docker)

로컬 DB는 `docker-compose.yml`로 띄웁니다. 팀원 모두 같은 버전·설정의 PostgreSQL을 쓰기 위해서입니다.

```bash
docker compose up -d
```

| 명령어 | 하는 일 |
| --- | --- |
| `docker compose up -d` | DB 컨테이너를 만들고 백그라운드로 실행 (작업 시작할 때) |
| `docker compose stop` | 멈추기만 함. 다시 `up -d`하면 그대로 재개 (작업 끝날 때) |
| `docker compose down` | 컨테이너 삭제. **데이터(볼륨)는 남음** |
| `docker compose down -v` | 볼륨까지 삭제 → **DB 데이터 전부 삭제**. DB를 처음부터 다시 만들 때만 사용 |

**접속 정보** (IntelliJ Database 탭 등에서 직접 볼 때)

| 항목 | 값 |
| --- | --- |
| Host / Port | `localhost` / `5432` |
| Database | `ilro` |
| User / Password | `ilro` / `ilro` |

> 각자 PC에서만 쓰는 개발용 DB라 비밀번호를 파일에 그대로 적어 두었습니다. 서버(RDS) DB 정보는 Git에 올리지 않습니다.

**자주 나는 에러**

| 에러 | 원인 | 해결 |
| --- | --- | --- |
| `localhost:5432 에 대한 연결이 거부되었습니다` | DB 컨테이너가 꺼져 있음 (PC 재부팅, Docker Desktop 종료 등) | Docker Desktop 켜고 `docker compose up -d` |
| `port is already allocated` (5432) | PC에 설치된 다른 PostgreSQL이 5432를 사용 중 | 기존 PostgreSQL 서비스를 끄고 다시 `up -d` |
| `The container name "/ilro-postgres" is already in use` | 이전에 만든 같은 이름 컨테이너가 남아 있음 | `docker rm ilro-postgres` 후 다시 `up -d` |

## 실행 방법

**DB를 먼저 띄운 뒤** 서버를 실행합니다.

**터미널**

```bash
# Mac / Linux / Git Bash
./gradlew bootRun

# Windows PowerShell (IntelliJ 기본 터미널)
.\gradlew.bat bootRun

# Windows cmd
gradlew.bat bootRun
```

**IntelliJ**

`IlroBackendApplication` 실행 (▶ 버튼)

- 기본 프로필은 `local`입니다 (`application.yml`의 `spring.profiles.active`)
- 프로필별 설정 파일

| 프로필 | 파일 | 용도 |
| --- | --- | --- |
| `local` | `application-local.yml` | 각자 PC에서 개발 |
| `dev` | `application-dev.yml` | 개발 서버 (`develop` 머지 시 배포, 팀 테스트용) |
| `prod` | `application-prod.yml` | 운영 서버 |

## 로컬 확인 URL

서버를 실행한 뒤 아래 주소로 정상 동작을 확인합니다.

| 확인할 것 | 주소 | 정상일 때 |
| --- | --- | --- |
| 서버 상태 | http://localhost:8080/actuator/health | 응답에 `"status":"UP"` 포함, `db` 항목도 `UP` |
| API 문서 (Swagger) | http://localhost:8080/swagger-ui.html | Swagger 화면이 열림 |

## DB 변경 규칙 (Flyway)

- 테이블 생성·변경은 **Flyway SQL 파일로만** 합니다
  - Hibernate는 Entity와 실제 테이블이 맞는지 **검사만** 합니다 (`ddl-auto: validate`). 안 맞으면 서버가 뜨지 않습니다
- 파일 위치: `src/main/resources/db/migration/`
- 파일 이름: `V{번호}__{설명}.sql` (**밑줄 2개**) — 예: `V1__init.sql`, `V2__add_owner_memo.sql`
- 서버가 켜질 때 아직 적용되지 않은 파일만 번호 순서대로 실행합니다. 적용 기록은 DB의 `flyway_schema_history` 테이블에 남습니다
- ⚠️ **`develop`에 머지된 마이그레이션 파일은 절대 수정하지 않습니다.** 바꿀 게 있으면 다음 번호의 새 파일을 추가합니다
  - 머지된 파일을 고치면 이미 적용한 팀원·서버에서 체크섬이 달라 서버가 뜨지 않습니다
- 로컬 DB가 꼬였을 때: `docker compose down -v` → `docker compose up -d` → 서버 실행 (V1부터 다시 적용)

## 패키지 구조

```
com.ilro.backend
├── domain/   도메인별 기능 (예: owner, inquiry, reservation)
│   └── {도메인}/  controller · service · repository · entity · dto
├── global/   모든 도메인이 따르는 공통 규칙
│   ├── common/response/  공통 응답 (ApiResponse)
│   ├── config/           설정 (CORS, JPA Auditing)
│   ├── entity/           공통 Entity 부모 (BaseTimeEntity 등)
│   └── exception/        에러 코드 · 전역 예외 처리
└── infra/    외부 서비스 연동
    └── sms/              문자 발송 (이후 kakao, s3, push 추가)
```

### 공통 클래스 사용법

**시간 칸 (JPA Auditing)** — 테이블의 시간 칸에 맞춰 상속하면 저장·수정할 때 자동으로 채워집니다.

| 테이블 시간 칸 | Entity |
| --- | --- |
| `created_at` + `updated_at` | `extends BaseTimeEntity` |
| `created_at`만 | `extends BaseCreatedEntity` |
| 없음 | 상속하지 않음 |

- 타입은 `Instant`입니다 (DB의 `TIMESTAMPTZ`와 맞춤)

**응답·에러** — API 응답은 `ApiResponse`로 감싸고, 에러는 `ErrorCode`를 담은 `BusinessException`을 던지면 `GlobalExceptionHandler`가 공통 형식으로 응답합니다.

**문자 발송** — `SmsSender`를 주입받아 `send(전화번호, 내용)`만 호출합니다.

- 결과는 `SENT` / `FAILED`로 돌려주며 **예외를 던지지 않습니다**
- `local`·`dev`에서는 `LogSmsSender`가 실제 문자 대신 **콘솔에 로그로 출력**합니다 (전화번호는 가운데를 가림). 인증번호는 IntelliJ 콘솔에서 확인하세요
- `prod`에서는 실제 문자 발송 구현체가 사용됩니다 (추후 추가)

## 더 자세한 가이드

개발 컨벤션(브랜치·커밋·이슈·PR), 자주 막히는 문제는 노션에 정리되어 있습니다.

- 노션 개발 가이드: https://app.notion.com/p/3e024a392ba980b0b2cddecb2fbd2c2c
