# modu-playlist (mopl)

> "흩어진 콘텐츠를 하나의 플레이리스트로, 함께 보고 함께 이야기하세요!"

모두의 플리 - 콘텐츠(영화/드라마 등)를 모아 플레이리스트로 큐레이션하고, 다른 사용자와 함께 시청하며 소통할 수 있는 콘텐츠 플레이리스트 커뮤니티 서비스의 백엔드 서버입니다. - SB9기 4팀 고급프로젝트

![Coverage](https://raw.githubusercontent.com/jikang24/sb09-modu-playlist-team4/badges/.github/badges/coverage.svg)

> 📌 이 레포는 **SB9기 4팀 "판타스틱 4"** 팀 프로젝트를 fork하여, 포트폴리오 정리를 위해 README를 재구성한 버전입니다.
> 원본 레포: [jikang24/sb09-modu-playlist-team4](https://github.com/jikang24/sb09-modu-playlist-team4)
>
> **본인(전승현) 담당 영역**: AWS 인프라·CI/CD, WebSocket, DM, Redis

## Table of Contents

1. [My Contributions](#my-contributions)
2. [Core Features](#core-features)
3. [Technology Stack](#technology-stack)
4. [Troubleshooting](#troubleshooting)
5. [File Structure](#file-structure)
6. [Getting Started](#getting-started)
7. [API Documentation](#api-documentation)
8. [Team](#team)

---

## My Contributions

- **AWS 인프라 & CI/CD**: ECS(Fargate) 배포 파이프라인 구축, ALB/Nginx 리버스 프록시 구성, GitHub Actions 기반 배포 자동화
- **WebSocket 기반 실시간 DM**: STOMP 프로토콜 기반 다이렉트 메시지, 인증/인가 처리
- **Redis 활용**: Pub/Sub 기반 실시간 메시지 브로드캐스트, 캐싱 전략

관련 코드: [`dm`](src/main/java/com/mopl/domain/dm) · [`infra`](src/main/java/com/mopl/infra)

> 상세 트러블슈팅 내용은 아래 [Troubleshooting](#troubleshooting) 섹션 참고

---

## Core Features

**인증/인가**: 이메일 회원가입 및 로그인, JWT 기반 인증, Google/Kakao OAuth2 소셜 로그인

**콘텐츠**: TMDB 연동 콘텐츠 수집, OpenSearch 기반 콘텐츠 검색

**플레이리스트**: 플레이리스트 생성/수정/삭제, 콘텐츠 담기

**리뷰**: 콘텐츠에 대한 리뷰 작성/수정/삭제

**팔로우**: 사용자 팔로우/언팔로우

**함께 시청**: 실시간 시청 세션(watching session) 생성 및 참여

**채팅/DM**: 콘텐츠 채팅, 사용자 간 다이렉트 메시지

**알림**: 사용자 행동 기반 실시간 알림

**배치**: ShedLock 기반 분산 락으로 다중 인스턴스 환경에서 안전한 배치 작업 수행

---

## Technology Stack

**Backend**: Java 17, Spring Boot 3.5, Spring Data JPA, QueryDSL, Spring Security, Spring Batch, Spring Validation

**Database / Cache**: PostgreSQL, Redis

**Search**: OpenSearch

**Messaging**: Apache Kafka

**Auth**: JWT (jjwt), OAuth2 Client (Google, Kakao)

**External API**: TMDB API

**Storage**: AWS S3

**Docs**: springdoc-openapi (Swagger)

**Object Mapping**: MapStruct

**Distributed Lock**: ShedLock (Redis)

**Test**: JUnit5, Spring Security Test, Spring Batch Test, Testcontainers, Awaitility, H2, JaCoCo

**Build / Infra**: Gradle, Docker, Docker Compose, Nginx

---

## Troubleshooting

### 1. Redis Pub/Sub 기반 DM 실시간 전달 실패

- **문제**: Redis를 경유해 발행한 DM 메시지가 WebSocket 클라이언트에게 전달되지 않음
- **원인**:
  - Redis 채널명은 콜론(`websocket:conversations:{id}:direct-messages`) 기반인데, 접두사만 제거하면 프론트가 구독 중인 슬래시 기반 경로(`/sub/conversations/{id}/direct-messages`)와 형식이 어긋남
  - 여기에 더해 AWS **ElastiCache Serverless가 PSUBSCRIBE(와일드카드 패턴 구독)를 지원하지 않아** `PatternTopic` 방식 자체가 서버 기동 시점에 실패
- **해결**: 패턴 구독을 포기하고 도메인별 **고정 채널**로 전환, 동적으로 바뀌는 값(대화방 ID 등)은 채널명이 아니라 payload의 `destination` 필드에 담아 전달
- **결과**: 실시간 메시지 전달 정상화 + 서버 기동 실패 문제 동시 해결

### 2. WebSocket(STOMP) 인증 정보 유실 — CONNECT는 성공, SUBSCRIBE/SEND는 실패

- **문제**: CONNECT 단계에서는 인증에 성공하지만, 이후 SUBSCRIBE/SEND 단계에서 인증 정보를 찾지 못해 거부(403)됨
- **원인 추적 과정**:
  1. `accessor.setUser()`로 CONNECT 시 인증 정보를 저장했으나, STOMP `Message`가 불변 객체라 이 호출이 실제 메시지에 반영되지 않음
  2. `MessageBuilder.createMessage()`로 새 메시지를 만들어 반영하도록 수정했지만, SEND 시점에서 여전히 인증 정보가 조회되지 않음
  3. `StompHeaderAccessor.getUser()`가 SEND 시점에는 CONNECT 때 저장한 인증 정보를 못 찾는 것을 확인
- **해결**: `setUser()` 방식을 버리고, `accessor.getSessionAttributes()`에 JWT Claims를 직접 저장 → CONNECT/SUBSCRIBE/SEND 전 구간에서 동일하게 sessionAttributes를 조회하도록 통일
- **추가 보강**: `StompAuthInterceptor`에 로그아웃된 토큰의 블랙리스트 체크를 추가(HTTP 필터와 검증 기준 일치), 단 이 체크가 Redis 장애로 막히면 WebSocket 전체가 막히므로 **fail-open**(장애 시 통과 + 에러 로그) 방식으로 가용성을 우선함

### 3. Nginx 리버스 프록시가 실제 트래픽 경로에서 완전히 빠져있던 문제

- **문제**: nginx task가 죽어도 서비스가 정상 동작 — 즉 "ECS Nginx 리버스 프록시 구성" 요구사항이 실질적으로 충족되지 않고 있었음
- **원인**: HTTPS 적용 작업 중 ALB 443(ACM 인증서) 리스너가 nginx를 건너뛰고 앱 대상 그룹에 직접 연결됨. 게다가 nginx의 upstream이 `ALB:80`을 가리키는 상태였는데, 80번 포트는 이미 https 리다이렉트 전용으로 바뀌어 있어 nginx가 살아있어도 앱에 도달하지 못하는(무한 리다이렉트) 상태였음
- **해결**:
  - `사용자 → ALB(443) → nginx → ALB(8080) → 앱` 구조로 재설계
  - nginx.conf의 upstream을 `ALB:80` → `ALB:8080`으로 변경, 헬스체크용 `/healthz` 추가
  - ALB에 8080 리스너 신설, nginx 전용 대상 그룹(`nginx-tg`) 생성
  - 443 리스너의 기본 전달 대상을 앱 대상 그룹 → `nginx-tg`로 전환
- **결과**: 실제로 리버스 프록시를 경유하는 구조로 정상화, 미션 요구사항 충족

### 4. ECS 배포 Health Check 설정으로 인한 배포 지연 (CD 71% 단축)

- **문제**: 배포 1회에 17분 이상 소요
- **원인**: Health Check Grace Period(180초)가 실제 정상 판정까지 걸리는 시간(약 255초)보다 짧아 새 태스크가 정상화되기 전에 조기 종료(SIGTERM)됨. 또한 ALB 정상 임계값이 5회 연속 성공으로 설정되어 판정까지의 대기 시간이 과도하게 길었음
- **해결**: Grace Period를 300초 이상으로 상향, ALB 정상 임계값을 2~3회로 하향
- **결과**: 배포(CD) 소요시간 **17분 → 5분으로 71% 단축**

---

## File Structure

```
src/main/java/com/mopl
├── domain
│   ├── auth              # 인증/인가, JWT, OAuth2
│   ├── user               # 사용자
│   ├── content            # 콘텐츠 (영화/드라마 등)
│   ├── playlist            # 플레이리스트
│   ├── review              # 리뷰
│   ├── follow              # 팔로우
│   ├── dm                  # 다이렉트 메시지 ⭐ (본인 담당)
│   ├── contentchat          # 콘텐츠 채팅
│   ├── conversation         # 대화 ⭐ (본인 담당)
│   ├── watchingsession       # 함께 시청 세션
│   ├── notification          # 알림
│   └── batch                # 배치 작업
├── global                   # 공통 설정, 예외 처리, 유틸리티
└── infra                    # 외부 연동 (S3, OpenSearch, Kafka, TMDB 등) ⭐ (인프라 - 본인 담당)
```

---

## Getting Started

### 요구 사항

- JDK 17
- Docker / Docker Compose

### 환경 변수 설정

`.env.dev` 파일을 참고하여 프로젝트 루트에 필요한 환경 변수 파일(`.env` 또는 `.env.dev`)을 구성합니다.

```
SPRING_PROFILES_ACTIVE=
SERVER_PORT=
FRONTEND_BASE_URL=

# DB
DB_HOST=
DB_PORT=
DB_NAME=
DB_USERNAME=
DB_PASSWORD=

# Redis
REDIS_HOST=
REDIS_PORT=

# Kafka
KAFKA_BOOTSTRAP_SERVERS=
KAFKA_API_KEY=
KAFKA_API_SECRET=

# OpenSearch
OPENSEARCH_URI=
OPENSEARCH_INITIAL_ADMIN_PASSWORD=

# JWT
JWT_SECRET=
JWT_ACCESS_TOKEN_EXPIRY_MS=
JWT_REFRESH_TOKEN_EXPIRY_MS=

# Mail
MAIL_USERNAME=
MAIL_PASSWORD=

# TMDB
TMDB_API_KEY=
TMDB_ACCESS_TOKEN=

# AWS S3
CLOUD_AWS_REGION_STATIC=
CLOUD_AWS_CREDENTIALS_ACCESS_KEY=
CLOUD_AWS_CREDENTIALS_SECRET_KEY=

# OAuth2
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
```

### 로컬 실행 (Docker Compose)

```bash
docker compose up -d --build
```

Nginx(80), API 서버(내부 8080), PostgreSQL(5432), Redis(6379), Kafka(9092), OpenSearch(9200)가 함께 기동됩니다.

### 로컬 실행 (Gradle)

인프라(DB, Redis, Kafka, OpenSearch 등)만 별도로 띄운 뒤 애플리케이션을 직접 실행할 수 있습니다.

```bash
./gradlew bootRun
```

기본 프로파일은 `dev`이며, `local`/`test`/`prod` 프로파일은 `src/main/resources/application-{profile}.yml`에서 확인할 수 있습니다.

### 빌드

```bash
./gradlew build
```

### 테스트

```bash
./gradlew test
```

테스트 실행 후 JaCoCo 커버리지 리포트가 `build/reports/jacoco/test/html/index.html`에 생성됩니다.

---

## API Documentation

애플리케이션 실행 후 Swagger UI에서 API 명세를 확인할 수 있습니다.

```
http://localhost:8080/swagger-ui/index.html
```

---

## Team

SB9기 4팀 - 판타스틱 4

| 이름 | GitHub | 주요 담당 |
|------|--------|-----------|
| 강지원 | [@jikang24](https://github.com/jikang24) | 콘텐츠, 시청세션, Batch, 캐싱전략 구축 |
| 나은비 | [@nano-mm](https://github.com/nano-mm) | AWS, 플레이리스트, 알림, SSE, Kafka, OpenSearch |
| 박지은 | [@clover6559](https://github.com/clover6559) | 사용자, OAuth, 팔로우, 인증 및 보안 관리 |
| 전승현 | [@seunghyeonjeon57-dot](https://github.com/seunghyeonjeon57-dot) | AWS, WebSocket, DM, Redis, 인프라 구축 ([관련 코드](src/main/java/com/mopl/domain/dm)) |

---

## 구현 홈페이지

https://mopl-codeit.link/#/sign-in