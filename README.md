# modu-playlist (mopl)

> "흩어진 콘텐츠를 하나의 플레이리스트로, 함께 보고 함께 이야기하세요!"

모두의 플리 - 콘텐츠(영화/드라마 등)를 모아 플레이리스트로 큐레이션하고, 다른 사용자와 함께 시청하며 소통할 수 있는 콘텐츠 플레이리스트 커뮤니티 서비스의 백엔드 서버입니다. - SB9기 4팀 고급프로젝트

![Coverage](https://raw.githubusercontent.com/jikang24/sb09-modu-playlist-team4/badges/.github/badges/coverage.svg)

> 📌 이 레포는 **SB9기 4팀 "판타스틱 4"** 팀 프로젝트를 fork하여, 포트폴리오 정리를 위해 README를 재구성한 버전입니다.
> 원본 레포: [jikang24/sb09-modu-playlist-team4](https://github.com/jikang24/sb09-modu-playlist-team4)
>
> **본인(전승현) 담당 영역**: WebSocket, DM, Redis(캐싱·인증·분산락), AWS 인프라·CI/CD

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

- **WebSocket 기반 실시간 DM**: 콘텐츠 채팅 및 사용자 간 다이렉트 메시지 기능 설계·구현
- **Redis 활용**: 캐싱 전략 수립, 인증(토큰) 관리, ShedLock 기반 분산 락으로 다중 인스턴스 환경에서의 배치 작업 동시성 문제 해결
- **AWS 인프라 & CI/CD**: 배포 파이프라인 구축 및 인프라 운영

관련 코드: [`dm`](src/main/java/com/mopl/domain/dm) · [`batch`](src/main/java/com/mopl/domain/batch)

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

> ⚠️ 아래는 본인이 직접 담당하고 해결한 이슈 중심으로 작성되었습니다. STAR-T 회고 내용을 참고해 구체적인 문제/원인/해결/결과를 채워 넣으세요.

### 1. 다중 인스턴스 환경에서 배치 작업 중복 실행 문제
- **문제**: (예: 여러 서버 인스턴스가 동시에 배치 작업을 실행하면서 중복 처리 발생)
- **원인**: (예: 분산 환경에서 스케줄러가 각 인스턴스마다 독립적으로 동작)
- **해결**: ShedLock(Redis) 기반 분산 락 도입으로 단일 인스턴스만 배치 작업을 수행하도록 제어
- **결과**: (예: 중복 실행으로 인한 데이터 정합성 오류 X% 감소, 또는 관련 장애 재발 방지)

### 2. WebSocket 기반 실시간 DM 처리 시 이슈
- **문제**: (예: 동시 접속자 증가 시 연결 관리 이슈, 메시지 유실/지연 등)
- **원인**: (구체적 원인)
- **해결**: (구체적 해결 방법)
- **결과**: (수치화된 결과)

### 3. Redis 캐싱/인증 관련 이슈
- **문제**: (예: 캐시 정합성 문제, 토큰 갱신 시 동시성 이슈 등)
- **원인**: (구체적 원인)
- **해결**: (구체적 해결 방법)
- **결과**: (수치화된 결과)

---

## File Structure

```
src/main/java/com/mopl
├── domain
│   ├── auth              # 인증/인가, JWT, OAuth2
│   ├── user              # 사용자
│   ├── content           # 콘텐츠 (영화/드라마 등)
│   ├── playlist          # 플레이리스트
│   ├── review             # 리뷰
│   ├── follow             # 팔로우
│   ├── dm                 # 다이렉트 메시지 ⭐ (본인 담당)
│   ├── contentchat        # 콘텐츠 채팅
│   ├── conversation       # 대화
│   ├── watchingsession    # 함께 시청 세션
│   ├── notification       # 알림
│   └── batch              # 배치 작업 ⭐ (본인 담당 - 분산 락)
├── global                 # 공통 설정, 예외 처리, 유틸리티
└── infra                  # 외부 연동 (S3, OpenSearch, Kafka, TMDB 등) ⭐ (인프라 - 본인 담당)
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