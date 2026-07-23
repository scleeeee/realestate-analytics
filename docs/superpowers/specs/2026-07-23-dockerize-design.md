# 도커라이즈 — 설계 문서

- 작성일: 2026-07-23
- 목적: `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`에서 예정했던 다음 단계. `ingest`/`api`/`web` 3개 모듈을 컨테이너화하고, 기존 `docker-compose.yml`(mariadb/redis만 포함)을 확장해 `docker compose up`으로 전체 스택을 띄울 수 있게 한다.
- 범위: 로컬에서 실무급 품질(멀티스테이지 빌드, non-root, healthcheck)로 도커라이즈하는 것까지. 클라우드 실배포/k8s 매니페스트는 이번 범위 밖(원 설계 문서와 동일).

## 아키텍처

기존 `docker-compose.yml`을 확장한다. 별도 compose 파일로 분리하지 않고 하나로 통합 — `docker compose up`으로 DB/캐시부터 앱까지 한 번에 뜨는 구조가 데모/개발 편의성 모두에 유리하다.

```
docker-compose.yml
├── mariadb (기존, healthcheck 추가)
├── redis   (기존, healthcheck 추가)
├── ingest  (신규, build: ./ingest, one-shot — depends_on mariadb: service_healthy)
├── api     (신규, build: ./api, 상시 기동 — depends_on mariadb+redis: service_healthy)
└── web     (신규, build: ./web, nginx — depends_on api, /api를 api:8080으로 리버스 프록시)
```

내부 네트워킹은 컴포즈 기본 브리지 네트워크의 서비스명(`mariadb`, `redis`, `api`)으로 해결하며 컨테이너 내부 포트(3306/6379/8080)를 그대로 사용한다. 호스트 매핑 포트(13306/16379, 로컬 IDE 실행용)는 그대로 유지 — 로컬 개발 워크플로(Gradle에서 직접 `bootRun`, benchmark 스크립트)가 이 포트에 의존하고 있어 변경하지 않는다.

## 컴포넌트

### `ingest/Dockerfile`
멀티스테이지: `eclipse-temurin:21-jdk-alpine`(또는 동등)으로 Gradle 빌드 → `eclipse-temurin:21-jre-alpine` 런타임. non-root 유저로 실행. `docker compose run --rm ingest`로 필요할 때만 실행하고 job 종료와 함께 컨테이너도 종료 — Spring Batch job의 실제 시맨틱(적재 후 끝남)과 일치하며, 상시 서비스로 등록하지 않는다.

### `api/Dockerfile`
같은 멀티스테이지 패턴, JRE 런타임, non-root. 상시 기동. **Spring Boot Actuator를 신규 의존성으로 추가**(`spring-boot-starter-actuator`)해서 `/actuator/health`를 표준 healthcheck 엔드포인트로 사용한다 — 임의 비즈니스 엔드포인트를 healthcheck로 재사용하는 것보다 명확하고, 이후 모니터링 확장(metrics 등)에도 자연스럽게 이어진다. `management.endpoint.health.show-details`는 기본값(never)으로 두고 `/actuator/health`만 노출한다.

### `web/Dockerfile`
멀티스테이지: `node:20-alpine`으로 `npm run build` → `nginx:alpine`이 정적 파일 서빙. nginx 설정에서:
- `/api/*` → `api:8080`으로 리버스 프록시 (dev 서버의 vite proxy와 동일한 역할)
- SPA 라우팅을 위해 `try_files $uri /index.html` (React Router 대응)

### `docker-compose.yml` 변경
`ingest`/`api` 서비스에 환경변수 오버라이드로 DB/Redis 접속 정보를 주입한다 (Spring 프로파일 분리 대신, 12-factor 스타일 — 이후 k8s로 옮겨도 ConfigMap/Secret 매핑으로 그대로 이어짐):
```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:mariadb://mariadb:3306/realestate
  SPRING_DATASOURCE_USERNAME: realestate
  SPRING_DATASOURCE_PASSWORD: realestate
  SPRING_DATA_REDIS_HOST: redis      # api만
  SPRING_DATA_REDIS_PORT: 6379       # api만
```
`application.yml`의 `localhost` 기본값은 그대로 둔다(로컬 IDE 실행 시 host-mapped 포트로 계속 동작).

`mariadb`/`redis`에 `healthcheck` 지시자를 추가하고, `ingest`/`api`는 `depends_on: <service>: condition: service_healthy`로 기동 순서를 보장한다. `web`은 `depends_on: api`(단순 시작 순서만, healthy 대기는 필수 아님 — nginx가 뜬 후 api가 조금 늦게 준비돼도 요청 시점엔 이미 떠 있을 가능성이 높고, 프록시 실패는 흔한 개발 편의 트레이드오프로 허용).

## 데이터 흐름
원 설계 문서와 동일 (국토부 API → ingest → MariaDB → api ← Redis 캐시 → web). 컨테이너화로 인한 흐름 변경은 없고, 네트워크 경로만 `localhost:포트` → `서비스명:내부포트`로 바뀐다.

## 에러 처리
- `ingest`: one-shot 컨테이너 실행 결과(exit code)로 성공/실패 판단. job 자체의 재시작/스킵 로직은 기존 Spring Batch 구현 그대로.
- `api`: `/actuator/health`가 DB/Redis 연결 실패 시 DOWN을 보고하도록 기본 Actuator health indicator(datasource, redis)를 그대로 사용 — 별도 커스텀 인디케이터는 만들지 않는다.
- `web`: nginx가 `api` 컨테이너에 연결 실패 시 502를 반환 — 프론트 쪽 에러 바운더리/컴포넌트 레벨 에러 처리는 기존 구현 그대로 재사용.

## 테스트
- 각 Dockerfile 빌드가 성공하는지 (`docker compose build`)
- `docker compose up -d mariadb redis api web` 후 컨테이너화된 스택 대상으로 브라우저 스모크테스트(검색 플로우 + 지역 상세 시세추이 차트) 재실행 — 로컬 `bootRun`/`vite dev`로 이미 확인한 것과 동일한 시나리오를 컨테이너 버전에서 재검증
- `docker compose run --rm ingest`를 별도로 실행해 정상 종료(exit 0) 확인
- `api`의 `/actuator/health` 응답이 `{"status":"UP"}`인지 확인

## 범위 밖 (Out of scope)
- 클라우드 실배포, k8s 매니페스트 (원 설계 문서와 동일하게 이후 별도 진행)
- CI/CD 파이프라인 (이미지 빌드/푸시 자동화)
- 컨테이너 리소스 제한(cpu/memory limits) 튜닝 — 로컬 데모 환경이라 우선순위 낮음
