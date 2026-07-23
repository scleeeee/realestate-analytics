# Dockerize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Note on this project:** For realestate-analytics specifically, the human partner wants to write/pair on the code directly rather than have it built autonomously by subagents — see `docs/superpowers/plans/2026-07-21-api-module.md` and the design discussion that produced this plan. Treat this plan as a shared design reference to implement together in-session, not as a queue to dispatch to background implementer subagents.

**Goal:** Containerize `ingest`, `api`, and `web`, and extend the existing `docker-compose.yml` (currently just `mariadb`+`redis`) so `docker compose up` brings up the full stack.

**Architecture:** Multi-stage Dockerfiles per module (JDK/Node build stage → minimal JRE/nginx runtime stage), wired into one `docker-compose.yml`. Containers reach `mariadb`/`redis` by Docker Compose service name instead of `localhost:<host-mapped-port>`, via environment-variable overrides (12-factor style, no new Spring profile). `ingest` runs as a one-shot job (`docker compose run --rm ingest`), not a standing service.

**Tech Stack:** Docker, Docker Compose, `eclipse-temurin:21-{jdk,jre}-alpine`, `node:20-alpine` + `nginx:alpine`, Spring Boot Actuator (new dependency on `api`).

**Note on scope:** This is the step after all 4 module plans (`ingest`/`api`/`web`/`benchmark`), per `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`'s "배포" section and `docs/superpowers/specs/2026-07-23-dockerize-design.md`.

---

### Design decisions locked in during planning discussion

| Decision | Choice | Reason |
|---|---|---|
| Compose structure | Extend the single existing `docker-compose.yml`, not a separate file | One `docker compose up` for the whole stack — simplest for local dev/demo. |
| Internal networking | Compose service names (`mariadb`, `redis`, `api`) + container-internal ports (3306/6379/8080) | Host-mapped ports (13306/16379) stay as-is for local IDE `bootRun` and the `benchmark` scripts, which depend on them. |
| Config override mechanism | Environment variables in `docker-compose.yml` (`SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST/PORT`), not a `application-docker.yml` profile | 12-factor style; carries over unchanged if this ever moves to k8s ConfigMap/Secret env injection. |
| `api` healthcheck | Add `spring-boot-starter-actuator`, use `/actuator/health` | Standard, extensible (metrics later) — not a bespoke health controller. |
| `web` serving | Multi-stage `node:20-alpine` build → `nginx:alpine` static serving + `/api` reverse proxy + SPA fallback (`try_files $uri /index.html`) | Standard production pattern for a Vite SPA; small final image. |
| `ingest` container lifecycle | One-shot via `docker compose run --rm ingest`, excluded from default `docker compose up` via a compose `profiles: ["tools"]` tag | Matches the real semantics of a Spring Batch job (runs, finishes, exits) instead of an idle standing service. |
| **`ingest` process exit** | **New: `IngestApplication.main()` now calls `System.exit(SpringApplication.exit(context))` when `ingest.run-on-startup=true`** | Flagging this explicitly — it's a small scope addition beyond "just add Dockerfiles". Today `ingest` boots an embedded Tomcat (it depends on `spring-boot-starter-web`) and **never exits** even after the batch job(s) finish, whether `run-on-startup` is `true` or `false` — verified by reading `IngestRunner`/`IngestApplication` while planning this. Without this change, "one-shot container that exits when done" (the stated design) would be false: the container would just sit there after the job completes, and `docker compose run --rm ingest` would hang until manually killed. The check is gated on `run-on-startup` being `true` so default (non-containerized, `false`) behavior is untouched — existing `IngestRunnerTest` tests construct `IngestRunner` directly and don't touch `main()`, so they're unaffected. |
| Docker build context for `ingest`/`api` | Repo root (`.`), `dockerfile: api/Dockerfile` / `dockerfile: ingest/Dockerfile`, selectively `COPY`ing only what each module's Gradle build needs | Both are subprojects of one multi-module Gradle build (`settings.gradle.kts` includes both) — Gradle needs the *other* module's `build.gradle.kts` to configure the build, but not its `src/`. Copying only what's needed keeps each image's Docker layer cache from being invalidated by unrelated module changes. |
| Data for post-dockerize smoke test | `benchmark/seed/generate-seed-data.js <small N>` against the new compose `mariadb`'s host-mapped port, after `docker compose run --rm ingest` has applied the Flyway schema migration | The dockerized stack starts with an empty DB (no `MOLIT_SERVICE_KEY` in this environment, so a real `ingest` run yields zero rows) — reuses the same synthetic-data approach already validated for `benchmark`. |

---

### Task 1: Add Spring Boot Actuator health endpoint to `api`

**Files:**
- Modify: `api/build.gradle.kts:12`
- Modify: `api/src/test/resources/application.yml`
- Test: `api/src/test/java/com/realestate/api/web/ActuatorHealthTest.java` (new)

**Design:** Follows the same `@Testcontainers @SpringBootTest @AutoConfigureMockMvc` + `MariaDBContainer` pattern already used in `TransactionSearchControllerTest`. Redis's health indicator is disabled for this test (`management.health.redis.enabled: false` in the test-only `application.yml`) because — like the rest of this test suite — there's no Redis Testcontainer wired up (Redis connections are lazy, so existing tests already run fine without a live Redis; but Actuator's health check actively pings it, which would report the whole endpoint `DOWN` otherwise).

- [ ] **Step 1: Write the failing test**

Create `api/src/test/java/com/realestate/api/web/ActuatorHealthTest.java`:
```java
package com.realestate.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api:test --tests ActuatorHealthTest`
Expected: FAIL — `/actuator/health` doesn't exist yet, so `status().isOk()` fails (404, not 200).

- [ ] **Step 3: Add the Actuator dependency**

In `api/build.gradle.kts`, insert after line 12 (`implementation("org.springframework.boot:spring-boot-starter-data-redis")`):
```kotlin
    implementation("org.springframework.boot:spring-boot-starter-actuator")
```

- [ ] **Step 4: Disable the Redis health indicator for tests**

Add to `api/src/test/resources/application.yml` (top-level, alongside the existing `spring:` block):
```yaml
management:
  health:
    redis:
      enabled: false
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :api:test --tests ActuatorHealthTest`
Expected: PASS

- [ ] **Step 6: Run the full `api` suite to confirm nothing else broke**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add api/build.gradle.kts api/src/test/resources/application.yml api/src/test/java/com/realestate/api/web/ActuatorHealthTest.java
git commit -m "feat(api): add Actuator health endpoint for container healthchecks"
```

---

### Task 2: Make `ingest` exit after a one-shot run

**Files:**
- Modify: `ingest/src/main/java/com/realestate/ingest/IngestApplication.java`

**Design:** See the design-decisions table above for why. This only changes `main()`, not `IngestRunner`, so the existing `IngestRunnerTest` (which constructs `IngestRunner` directly) needs no changes. Not unit-tested here — exercising it for real means launching a JVM that calls `System.exit`, which isn't something to do inside the Gradle test JVM. It's verified for real in Task 8, where `docker compose run --rm ingest` either exits on its own (pass) or hangs (fail, plainly visible).

- [ ] **Step 1: Update `IngestApplication.java`**

Replace the full contents of `ingest/src/main/java/com/realestate/ingest/IngestApplication.java`:
```java
package com.realestate.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties(IngestProperties.class)
public class IngestApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(IngestApplication.class, args);
        boolean runOnStartup = context.getEnvironment()
            .getProperty("ingest.run-on-startup", Boolean.class, false);
        if (runOnStartup) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
```

- [ ] **Step 2: Confirm the existing ingest suite still passes unchanged**

Run: `./gradlew :ingest:test`
Expected: BUILD SUCCESSFUL (this change doesn't touch anything the current tests exercise)

- [ ] **Step 3: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/IngestApplication.java
git commit -m "fix(ingest): exit the process after a run-on-startup batch run completes"
```

---

### Task 3: Root `.dockerignore`

**Files:**
- Create: `.dockerignore`

**Design:** Keeps the build context sent to the Docker daemon small for the `api`/`ingest` builds (context is the repo root — see design-decisions table). Without this, every `docker build` would upload `.git`, both Node projects' `node_modules`, and every module's `build/` output directory.

- [ ] **Step 1: Create `.dockerignore`**

```
.git
.worktrees
.idea
.vscode
docs
**/build
web/node_modules
web/dist
benchmark/node_modules
```

- [ ] **Step 2: Commit**

```bash
git add .dockerignore
git commit -m "chore: add root .dockerignore"
```

---

### Task 4: `api/Dockerfile`

**Files:**
- Create: `api/Dockerfile`

**Design:** Build context will be the repo root (wired up in Task 7's `docker-compose.yml`). Only `gradlew`, the wrapper, both modules' `build.gradle.kts` (Gradle needs `ingest`'s to configure the multi-project build, but not its `src/`), and `api/src` are copied — so changes to `ingest/src` or `web/` never invalidate this image's build cache.

- [ ] **Step 1: Create `api/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY api/build.gradle.kts api/build.gradle.kts
COPY ingest/build.gradle.kts ingest/build.gradle.kts
COPY api/src api/src

RUN ./gradlew :api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/api/build/libs/api-0.1.0.jar app.jar
USER spring:spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Build the image**

Run: `docker build -f api/Dockerfile -t realestate-api:local .`
Expected: `Successfully tagged realestate-api:local` (or equivalent BuildKit "naming to ... done" output), no errors.

- [ ] **Step 3: Sanity-check the runtime image contents**

Run: `docker run --rm --entrypoint sh realestate-api:local -c "ls -la /app && whoami"`
Expected: shows `app.jar` owned appropriately, and `whoami` prints `spring` (confirms non-root).

Full runtime behavior (DB/Redis connectivity, `/actuator/health` actually returning UP) is verified in Task 8 once the image is wired into `docker-compose.yml` and can reach `mariadb`/`redis` by service name — a standalone `docker run` here has no database to connect to.

- [ ] **Step 4: Commit**

```bash
git add api/Dockerfile
git commit -m "feat(api): add Dockerfile"
```

---

### Task 5: `ingest/Dockerfile`

**Files:**
- Create: `ingest/Dockerfile`

**Design:** Same pattern as Task 4, mirrored for `ingest`. No `EXPOSE`/`HEALTHCHECK` — it's a one-shot job, not a service other containers wait on.

- [ ] **Step 1: Create `ingest/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
RUN chmod +x gradlew

COPY api/build.gradle.kts api/build.gradle.kts
COPY ingest/build.gradle.kts ingest/build.gradle.kts
COPY ingest/src ingest/src

RUN ./gradlew :ingest:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/ingest/build/libs/ingest-0.1.0.jar app.jar
USER spring:spring
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Build the image**

Run: `docker build -f ingest/Dockerfile -t realestate-ingest:local .`
Expected: builds successfully, no errors.

- [ ] **Step 3: Sanity-check the runtime image contents**

Run: `docker run --rm --entrypoint sh realestate-ingest:local -c "ls -la /app && whoami"`
Expected: shows `app.jar`, `whoami` prints `spring`.

- [ ] **Step 4: Commit**

```bash
git add ingest/Dockerfile
git commit -m "feat(ingest): add Dockerfile"
```

---

### Task 6: `web/Dockerfile` + nginx config

**Files:**
- Create: `web/Dockerfile`
- Create: `web/nginx.conf`
- Create: `web/.dockerignore`

**Design:** Build context here is `./web` (not the repo root — `web` isn't part of the Gradle multi-project build, so it doesn't need sibling module files). `web/.dockerignore` is load-bearing, not just an optimization: without it, `COPY . .` after `npm ci` would copy the host's local `node_modules` (installed for Windows) over the container's freshly-installed Linux one.

- [ ] **Step 1: Create `web/.dockerignore`**

```
node_modules
dist
```

- [ ] **Step 2: Create `web/nginx.conf`**

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://api:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location / {
        try_files $uri /index.html;
    }
}
```

- [ ] **Step 3: Create `web/Dockerfile`**

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 4: Build the image**

Run: `docker build -f web/Dockerfile -t realestate-web:local web`
Expected: builds successfully — note the context argument is `web`, not `.`, since this Dockerfile doesn't need the repo root.

- [ ] **Step 5: Sanity-check the built image**

Run: `docker run --rm realestate-web:local sh -c "ls /usr/share/nginx/html && cat /etc/nginx/conf.d/default.conf | head -5"`
Expected: lists `index.html` and built JS/CSS assets; shows the nginx config's `listen 80;` line.

Full runtime behavior (the `/api` proxy actually reaching a live `api`) is verified in Task 8.

- [ ] **Step 6: Commit**

```bash
git add web/Dockerfile web/nginx.conf web/.dockerignore
git commit -m "feat(web): add Dockerfile with nginx static serving and /api reverse proxy"
```

---

### Task 7: Extend `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml` (full rewrite — small file)

**Design:** Adds healthchecks to `mariadb`/`redis` so `depends_on: condition: service_healthy` on `ingest`/`api` actually means something. `ingest` gets `profiles: ["tools"]` so it's excluded from a bare `docker compose up` and only runs via `docker compose run --rm ingest`. `web`'s `depends_on: api` is the short list form (no health condition) — see design doc for why that's an acceptable trade-off here.

- [ ] **Step 1: Replace `docker-compose.yml`**

```yaml
services:
  mariadb:
    image: mariadb:11.4
    environment:
      MARIADB_ROOT_PASSWORD: realestate
      MARIADB_DATABASE: realestate
      MARIADB_USER: realestate
      MARIADB_PASSWORD: realestate
    ports:
      - "13306:3306"
    volumes:
      - mariadb-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mariadb-admin", "ping", "-h", "localhost", "-uroot", "-prealestate"]
      interval: 5s
      timeout: 5s
      retries: 10

  redis:
    image: redis:7.4
    ports:
      - "16379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  ingest:
    build:
      context: .
      dockerfile: ingest/Dockerfile
    environment:
      SPRING_DATASOURCE_URL: jdbc:mariadb://mariadb:3306/realestate
      SPRING_DATASOURCE_USERNAME: realestate
      SPRING_DATASOURCE_PASSWORD: realestate
      INGEST_RUN_ON_STARTUP: "true"
    depends_on:
      mariadb:
        condition: service_healthy
    profiles: ["tools"]

  api:
    build:
      context: .
      dockerfile: api/Dockerfile
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mariadb://mariadb:3306/realestate
      SPRING_DATASOURCE_USERNAME: realestate
      SPRING_DATASOURCE_PASSWORD: realestate
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: "6379"
    depends_on:
      mariadb:
        condition: service_healthy
      redis:
        condition: service_healthy

  web:
    build:
      context: ./web
      dockerfile: Dockerfile
    ports:
      - "8081:80"
    depends_on:
      - api

volumes:
  mariadb-data:
```

- [ ] **Step 2: Validate the compose file parses**

Run: `docker compose config --quiet`
Expected: no output, exit code 0 (compose's way of saying "this YAML is valid and resolves").

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: wire ingest/api/web into docker-compose.yml"
```

---

### Task 8: Full-stack verification

**Files:** none (verification only)

**Design:** The already-running `infra-and-ingest-mariadb-1`/`infra-and-ingest-redis-1` containers (left over from earlier module work, on the same host ports 13306/16379 this compose file also binds) must be stopped first or `docker compose up` will fail with "port already allocated" — this exact conflict was hit and diagnosed earlier in this project's history.

- [ ] **Step 1: Stop the leftover standalone infra containers**

Run: `docker stop infra-and-ingest-mariadb-1 infra-and-ingest-redis-1`
Expected: both container names printed (stopped, not removed — can be restarted later for local `bootRun` use if needed).

- [ ] **Step 2: Bring up the full stack**

Run: `docker compose up -d --build mariadb redis api web`
Expected: all 4 containers created and started.

- [ ] **Step 3: Wait for healthy status and confirm**

Run: `docker compose ps`
Expected: `mariadb` and `redis` show `(healthy)`; `api` shows `(healthy)` once its own `HEALTHCHECK` passes (may take up to the `start-period`, ~30s after the JVM boots).

- [ ] **Step 4: Confirm `api`'s health endpoint directly**

Run: `curl -s http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 5: Run the one-shot `ingest` job and confirm it actually exits**

Run: `docker compose run --rm ingest`
Expected: logs show the Flyway migration applying and the batch job(s) attempting (and failing — no `MOLIT_SERVICE_KEY` is set in this environment, which is fine and expected), then the process exits and control returns to the shell — this is the concrete proof that Task 2's `System.exit` change works. If this hangs instead of returning, that's a failure of Task 2, not of the compose wiring.

- [ ] **Step 6: Seed a small amount of data for the smoke test**

Run: `node benchmark/seed/generate-seed-data.js 2000`
Expected: `Seeding 2,000 rows...` followed by a completion line — this connects to `localhost:13306` (the same host-mapped port the new compose `mariadb` publishes), matching `benchmark/db.js`'s defaults.

- [ ] **Step 7: Confirm `api` serves the seeded data**

Run: `curl -s "http://localhost:8080/api/transactions?regionCode=11110&size=3"`
Expected: a JSON `items` array (non-empty, assuming region `11110` got at least one row in the random seed — if empty, rerun Step 6 with a larger N).

- [ ] **Step 8: Confirm `web`'s nginx reverse proxy reaches `api`**

Run: `curl -s "http://localhost:8081/api/transactions?regionCode=11110&size=3"`
Expected: same JSON body as Step 7, fetched through nginx on port 8081 instead of hitting `api` directly — proves the `/api` `proxy_pass` in `web/nginx.conf` works.

---

### Task 9: Browser smoke test against the containerized stack

**Files:** none (verification only)

**Design:** Repeats the same browser-driven check already done earlier against the local (non-Docker) `bootRun`/`vite dev` stack, this time against the containerized ports (`web` on 8081 instead of the Vite dev server on 5174).

- [ ] **Step 1: Run the search + region-detail-chart flow with Playwright**

Using the same approach as the earlier local smoke test (Playwright via `npx`, headless Chromium), navigate to `http://localhost:8081/`, select a region (e.g. `11110`), submit the search form, confirm results render, click through to the region detail page, and confirm the price-trend chart renders with no console errors.

Expected: search results list populated, chart renders, zero console errors — same outcome already confirmed for the non-Docker stack, now proving parity through the containerized nginx + api path.

- [ ] **Step 2: Tear down**

Run: `docker compose down`
Expected: all 4 containers stopped and removed (the `mariadb-data` named volume persists by default — not removed unless `-v` is passed, which this step deliberately doesn't do).

- [ ] **Step 3: Restart the leftover infra containers if still wanted for local IDE work**

Run: `docker start infra-and-ingest-mariadb-1 infra-and-ingest-redis-1` (optional — only if local `bootRun`-based development against that specific dataset is still needed).
