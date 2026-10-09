# backend

산학프로젝트 10조 **일로** 백엔드 API 서버 (Spring Boot)

## 기술 스택

- Java 21
- Spring Boot 4.1
- Spring Web MVC · Validation
- Spring Boot Actuator (서버 상태 확인)
- Springdoc OpenAPI (Swagger UI)
- Lombok

> DB(PostgreSQL) · Redis · Flyway · Docker Compose는 이후 PR에서 추가되며, 추가될 때 이 문서도 같이 갱신합니다.

## 필요 환경

- **JDK 21**
  - IntelliJ: `File → Project Structure → Project SDK`를 21로 설정

## 환경변수 설정

프로젝트 루트에서 `.env.example`을 복사해 `.env` 파일을 만듭니다.

```bash
cp .env.example .env
```

- `.env`에는 API 키 같은 **비밀값**이 들어가므로 **Git에 올리지 않습니다** (`.gitignore`에 등록되어 있음)
- 비밀값은 노션·디스코드 공개 채널에 붙여넣지 말고, 담당자에게 개인적으로 받습니다
- `.env`가 없어도 서버는 기본값으로 실행됩니다

| 변수 | 설명 | 기본값          |
| --- | --- |--------------|
| `SERVER_PORT` | 서버 포트 | `8080`       |
| `KAKAO_REST_API_KEY` | 카카오 REST API 키 (주소 → 좌표, 이동시간 조회) | 없음 · 팀원한테 요청 |

## 실행 방법

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
| 서버 상태 | http://localhost:8080/actuator/health | 응답에 `"status":"UP"` 포함 |
| API 문서 (Swagger) | http://localhost:8080/swagger-ui.html | Swagger 화면이 열림 |

## 더 자세한 가이드

개발 컨벤션(브랜치·커밋·이슈·PR), 자주 막히는 문제는 노션에 정리되어 있습니다.
추후에 로컬 개발 환경 세팅 가이드도 추가할 계획입니다.

- 노션 개발 가이드: https://app.notion.com/p/3e024a392ba980b0b2cddecb2fbd2c2c
