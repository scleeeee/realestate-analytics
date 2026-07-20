# Infra & Ingest Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the local infrastructure (MariaDB + Redis) and a working `ingest` module that pulls 아파트 매매 실거래가 data from the MOLIT open API and bulk-loads it into a RANGE-partitioned MariaDB table via a Spring Batch job.

**Architecture:** Gradle multi-module repo. `ingest` module: `MolitApiClient` (real HTTP + XML parsing impl, plus a fake test double) → `MolitApiItemReader` (paginates the client) → `RealEstateTransactionProcessor` (maps API DTO to domain record, computes `dealYm`) → `RealEstateTransactionWriter` (JdbcTemplate batch insert, bypassing JPA for bulk-load performance) wired together as a Spring Batch chunk-oriented step. Schema is managed by Flyway.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Spring Batch 5, Spring JDBC, Flyway, MariaDB 11.4, Testcontainers, JUnit 5, AssertJ, Mockito, Jackson XML, Gradle Kotlin DSL.

**Note on scope:** This is Plan 1 of 4 for the realestate-analytics project (see `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`). The `api` module (JPA/QueryDSL search + Redis caching), `web` module (Vue3), and `benchmark` scripts are separate follow-up plans, written after this one is implemented and reviewed — this plan only needs to produce a working, independently testable ingest pipeline.

---

### Task 1: Repo scaffold (Gradle multi-module)

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Create: `ingest/build.gradle.kts`
- Create: `ingest/src/main/java/com/realestate/ingest/IngestApplication.java`

- [x] **Step 1: Create root Gradle files**

`settings.gradle.kts`:
```kotlin
rootProject.name = "realestate-analytics"
include("ingest")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("java")
}

allprojects {
    group = "com.realestate"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
```

`.gitignore`:
```
.gradle/
build/
*.iml
.idea/
out/
.env
```

- [x] **Step 2: Create the ingest module build file**

`ingest/build.gradle.kts`:
```kotlin
plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:mariadb:1.20.1")
}
```

- [x] **Step 3: Create the Spring Boot application entry point**

`ingest/src/main/java/com/realestate/ingest/IngestApplication.java`:
```java
package com.realestate.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IngestApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
```

- [x] **Step 4: Generate the Gradle wrapper and verify the build**

Run: `gradle wrapper --gradle-version 8.10` (if Gradle isn't installed locally, open the folder in IntelliJ — it will generate the wrapper automatically on import)

Then run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL` (no tests exist yet, so `-x test` skips the empty test task)

> Note: local Gradle/JDK21 weren't available; wrapper bootstrap files were provided and `foojay-resolver-convention` added to `settings.gradle.kts` for toolchain auto-provisioning. `gradlew` also needed its executable bit fixed in a follow-up commit (`31a6374`).

- [x] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts .gitignore ingest/build.gradle.kts ingest/src gradle gradlew gradlew.bat
git commit -m "chore: scaffold Gradle multi-module project with ingest module"
```

Commits: `c8323b2`, `31a6374` (gradlew +x fix)

---

### Task 2: Local infra (MariaDB + Redis via docker-compose)

**Files:**
- Create: `docker-compose.yml`

- [x] **Step 1: Write docker-compose.yml**

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
      - "3306:3306"
    volumes:
      - mariadb-data:/var/lib/mysql

  redis:
    image: redis:7.4
    ports:
      - "6379:6379"

volumes:
  mariadb-data:
```

- [x] **Step 2: Start containers and verify**

Run: `docker compose up -d`
Expected: both `mariadb` and `redis` containers show `Up` in `docker compose ps`

Run: `docker exec -it realestate-analytics-mariadb-1 mariadb -urealestate -prealestate -e "SELECT 1"`
Expected: a result row with `1`

> Note: host ports remapped to `13306`/`16379` (see commit `dea677e`) because this machine already has a native MariaDB/Redis bound to 3306/6379. `application.yml` datasource URL uses `13306` accordingly.

- [x] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: add docker-compose for local MariaDB and Redis"
```

Commits: `239fd1f`, `dea677e` (port remap fix)

---

### Task 3: Partitioned schema via Flyway

> **Blocker resolved.** Root cause was NOT Windows npipe/TCP transport (WSL2's native `/var/run/docker.sock` hit the identical stub `400` response via `curl` from docker-java, while plain `curl` against the same socket worked fine) — it was a genuine **Testcontainers/docker-java version incompatibility with Docker Engine 29.x** (Docker Desktop 4.82.0). Confirmed via upstream reports (testcontainers-java issues #11235, #11419, #11422): Engine 29 requires Docker API ≥ 1.44; testcontainers 1.20.x's bundled docker-java client negotiates too old a version. Fix: upgraded `ingest/build.gradle.kts` to **testcontainers 2.0.5** (artifact IDs renamed in 2.x: `org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-mariadb`). Test suite is run from **WSL2** (`wsl -e bash -lc "cd /mnt/c/Users/.../infra-and-ingest && sh gradlew ..."`) since the Windows host still lacks a working local JDK/Gradle setup outside WSL; WSL now has JDK 21 installed (`sudo apt-get install -y openjdk-21-jdk`, `sudo update-alternatives --set java ...`) and shares the Docker Desktop engine natively.
>
> `SchemaMigrationTest.java` and `application.yml` are written (not yet committed — files exist on disk at their target paths). `application.yml` datasource URL points at `localhost:13306` (matches the docker-compose port remap). Step 2 (verify test fails) is confirmed: `AssertionFailedError: Expecting actual: [] to contain exactly: ["p2020", ..., "pmax"]`.
>
> The migration SQL itself (`V1__create_real_estate_transaction.sql`) has not been started — per user's request, that file is to be co-written (not fully authored by the agent).
>
> Docker containers (`docker-compose.yml`) are up and healthy on ports 13306/16379.

**Files:**
- Create: `ingest/src/main/resources/db/migration/V1__create_real_estate_transaction.sql`
- Create: `ingest/src/main/resources/application.yml`
- Test: `ingest/src/test/java/com/realestate/ingest/SchemaMigrationTest.java`

- [x] **Step 1: Write the failing test**

`ingest/src/test/java/com/realestate/ingest/SchemaMigrationTest.java`:
```java
package com.realestate.ingest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaMigrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @Test
    void migratesAndCreatesPartitionedTable() throws Exception {
        Flyway.configure()
            .dataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection conn = DriverManager.getConnection(
                mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
                 "WHERE TABLE_NAME = 'real_estate_transaction' AND PARTITION_NAME IS NOT NULL " +
                 "ORDER BY PARTITION_ORDINAL_POSITION")) {
            List<String> partitions = new ArrayList<>();
            while (rs.next()) partitions.add(rs.getString(1));
            assertThat(partitions).containsExactly(
                "p2020", "p2021", "p2022", "p2023", "p2024", "p2025", "pmax");
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests SchemaMigrationTest`
Expected: FAIL — `db/migration` location has no migration files, Flyway finds nothing to migrate, or the table doesn't exist yet.

- [x] **Step 3: Write the migration**

`ingest/src/main/resources/db/migration/V1__create_real_estate_transaction.sql`:
```sql
CREATE TABLE real_estate_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    region_code VARCHAR(10) NOT NULL,
    legal_dong VARCHAR(60) NOT NULL,
    apt_name VARCHAR(120) NOT NULL,
    exclusive_area DECIMAL(6,2) NOT NULL,
    deal_amount BIGINT NOT NULL,
    deal_year SMALLINT NOT NULL,
    deal_month TINYINT NOT NULL,
    deal_day TINYINT NOT NULL,
    deal_ym INT NOT NULL,
    floor SMALLINT,
    build_year SMALLINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, deal_ym),
    KEY idx_region_ym (region_code, deal_ym)
) ENGINE=InnoDB
PARTITION BY RANGE (deal_ym) (
    PARTITION p2020 VALUES LESS THAN (202101),
    PARTITION p2021 VALUES LESS THAN (202201),
    PARTITION p2022 VALUES LESS THAN (202301),
    PARTITION p2023 VALUES LESS THAN (202401),
    PARTITION p2024 VALUES LESS THAN (202501),
    PARTITION p2025 VALUES LESS THAN (202601),
    PARTITION pmax VALUES LESS THAN MAXVALUE
);
```

`ingest/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/realestate
    username: realestate
    password: realestate
  batch:
    job:
      enabled: false
  flyway:
    locations: classpath:db/migration

molit:
  base-url: https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev
  service-key: ${MOLIT_SERVICE_KEY:}

ingest:
  run-on-startup: false
  region-codes:
    - "11110"
    - "11140"
  deal-yms:
    - "202301"
    - "202302"
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests SchemaMigrationTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add ingest/src/main/resources ingest/src/test/java/com/realestate/ingest/SchemaMigrationTest.java
git commit -m "feat: add partitioned real_estate_transaction schema via Flyway"
```

---

### Task 4: MOLIT API client interface, DTO, and fake test double

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/client/AptTradeItem.java`
- Create: `ingest/src/main/java/com/realestate/ingest/client/MolitApiClient.java`
- Create: `ingest/src/test/java/com/realestate/ingest/client/FakeMolitApiClient.java`

- [x] **Step 1: Create the DTO and interface**

`ingest/src/main/java/com/realestate/ingest/client/AptTradeItem.java`:
```java
package com.realestate.ingest.client;

public record AptTradeItem(
    String regionCode,
    String legalDong,
    String aptName,
    double exclusiveArea,
    long dealAmount,
    int dealYear,
    int dealMonth,
    int dealDay,
    Integer floor,
    Integer buildYear
) {}
```

`ingest/src/main/java/com/realestate/ingest/client/MolitApiClient.java`:
```java
package com.realestate.ingest.client;

import java.util.List;

public interface MolitApiClient {
    List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows);
}
```

- [x] **Step 2: Create the fake test double**

`ingest/src/test/java/com/realestate/ingest/client/FakeMolitApiClient.java`:
```java
package com.realestate.ingest.client;

import java.util.List;
import java.util.Map;

public class FakeMolitApiClient implements MolitApiClient {

    private final Map<String, List<AptTradeItem>> pages;

    public FakeMolitApiClient(Map<String, List<AptTradeItem>> pages) {
        this.pages = pages;
    }

    @Override
    public List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows) {
        String key = regionCode + ":" + dealYm + ":" + pageNo;
        return pages.getOrDefault(key, List.of());
    }
}
```

- [x] **Step 3: Compile to verify no errors**

Run: `./gradlew :ingest:compileJava :ingest:compileTestJava`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/client ingest/src/test/java/com/realestate/ingest/client
git commit -m "feat: add MolitApiClient interface, AptTradeItem DTO, and fake test double"
```

Commits: `87e1f30`

---

### Task 5: RealEstateTransaction domain record + processor

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/domain/RealEstateTransaction.java`
- Create: `ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionProcessor.java`
- Test: `ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionProcessorTest.java`

- [ ] **Step 1: Write the failing test**

`ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionProcessorTest.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealEstateTransactionProcessorTest {

    @Test
    void computesDealYmFromYearAndMonth() {
        var processor = new RealEstateTransactionProcessor();
        var item = new AptTradeItem("11110", "종로구", "테스트아파트", 84.95, 95000, 2023, 7, 15, 5, 2005);

        RealEstateTransaction result = processor.process(item);

        assertThat(result.dealYm()).isEqualTo(202307);
        assertThat(result.aptName()).isEqualTo("테스트아파트");
        assertThat(result.dealAmount()).isEqualTo(95000);
        assertThat(result.floor()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests RealEstateTransactionProcessorTest`
Expected: FAIL — `RealEstateTransaction` and `RealEstateTransactionProcessor` don't exist yet (compile error)

- [ ] **Step 3: Create the domain record**

`ingest/src/main/java/com/realestate/ingest/domain/RealEstateTransaction.java`:
```java
package com.realestate.ingest.domain;

public record RealEstateTransaction(
    String regionCode,
    String legalDong,
    String aptName,
    double exclusiveArea,
    long dealAmount,
    int dealYear,
    int dealMonth,
    int dealDay,
    int dealYm,
    Integer floor,
    Integer buildYear
) {}
```

- [ ] **Step 4: Create the processor**

`ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionProcessor.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.item.ItemProcessor;

public class RealEstateTransactionProcessor implements ItemProcessor<AptTradeItem, RealEstateTransaction> {

    @Override
    public RealEstateTransaction process(AptTradeItem item) {
        int dealYm = item.dealYear() * 100 + item.dealMonth();
        return new RealEstateTransaction(
            item.regionCode(),
            item.legalDong(),
            item.aptName(),
            item.exclusiveArea(),
            item.dealAmount(),
            item.dealYear(),
            item.dealMonth(),
            item.dealDay(),
            dealYm,
            item.floor(),
            item.buildYear()
        );
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests RealEstateTransactionProcessorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/domain ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionProcessor.java ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionProcessorTest.java
git commit -m "feat: add RealEstateTransaction domain record and processor"
```

---

### Task 6: JdbcTemplate batch writer

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionWriter.java`
- Test: `ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionWriterTest.java`

- [ ] **Step 1: Write the failing test**

`ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionWriterTest.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.domain.RealEstateTransaction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RealEstateTransactionWriterTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    static DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        var ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.mariadb.jdbc.Driver.class);
        ds.setUrl(mariadb.getJdbcUrl());
        ds.setUsername(mariadb.getUsername());
        ds.setPassword(mariadb.getPassword());
        dataSource = ds;

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @Test
    void writesChunkAndPersistsAllFields() {
        var writer = new RealEstateTransactionWriter(new JdbcTemplate(dataSource));
        var tx = new RealEstateTransaction(
            "11110", "종로구", "테스트아파트", 84.95, 95000,
            2023, 7, 15, 202307, 5, 2005);

        writer.write(new Chunk<>(List.of(tx)));

        var jdbcTemplate = new JdbcTemplate(dataSource);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM real_estate_transaction WHERE deal_ym = 202307", Integer.class);
        assertThat(count).isEqualTo(1);

        String aptName = jdbcTemplate.queryForObject(
            "SELECT apt_name FROM real_estate_transaction WHERE deal_ym = 202307", String.class);
        assertThat(aptName).isEqualTo("테스트아파트");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests RealEstateTransactionWriterTest`
Expected: FAIL — `RealEstateTransactionWriter` doesn't exist yet (compile error)

- [ ] **Step 3: Write the writer**

`ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionWriter.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.util.List;

public class RealEstateTransactionWriter implements ItemWriter<RealEstateTransaction> {

    private static final String INSERT_SQL = """
        INSERT INTO real_estate_transaction
            (region_code, legal_dong, apt_name, exclusive_area, deal_amount,
             deal_year, deal_month, deal_day, deal_ym, floor, build_year)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public RealEstateTransactionWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(Chunk<? extends RealEstateTransaction> chunk) {
        List<? extends RealEstateTransaction> items = chunk.getItems();
        jdbcTemplate.batchUpdate(INSERT_SQL, items, items.size(), (ps, tx) -> {
            ps.setString(1, tx.regionCode());
            ps.setString(2, tx.legalDong());
            ps.setString(3, tx.aptName());
            ps.setDouble(4, tx.exclusiveArea());
            ps.setLong(5, tx.dealAmount());
            ps.setInt(6, tx.dealYear());
            ps.setInt(7, tx.dealMonth());
            ps.setInt(8, tx.dealDay());
            ps.setInt(9, tx.dealYm());
            if (tx.floor() != null) {
                ps.setInt(10, tx.floor());
            } else {
                ps.setNull(10, Types.SMALLINT);
            }
            if (tx.buildYear() != null) {
                ps.setInt(11, tx.buildYear());
            } else {
                ps.setNull(11, Types.SMALLINT);
            }
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests RealEstateTransactionWriterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/batch/RealEstateTransactionWriter.java ingest/src/test/java/com/realestate/ingest/batch/RealEstateTransactionWriterTest.java
git commit -m "feat: add JdbcTemplate batch writer for real_estate_transaction"
```

---

### Task 7: Paginating item reader

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/batch/MolitApiItemReader.java`
- Test: `ingest/src/test/java/com/realestate/ingest/batch/MolitApiItemReaderTest.java`

- [ ] **Step 1: Write the failing test**

`ingest/src/test/java/com/realestate/ingest/batch/MolitApiItemReaderTest.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.FakeMolitApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MolitApiItemReaderTest {

    @Test
    void readsAcrossMultiplePagesThenReturnsNull() {
        var item1 = new AptTradeItem("11110", "종로구", "A아파트", 84.9, 90000, 2023, 7, 1, 3, 2000);
        var item2 = new AptTradeItem("11110", "종로구", "B아파트", 59.8, 70000, 2023, 7, 2, 10, 2010);

        var fakeClient = new FakeMolitApiClient(Map.of(
            "11110:202307:1", List.of(item1),
            "11110:202307:2", List.of(item2)
        ));

        var reader = new MolitApiItemReader(fakeClient, "11110", "202307");

        assertThat(reader.read()).isEqualTo(item1);
        assertThat(reader.read()).isEqualTo(item2);
        assertThat(reader.read()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests MolitApiItemReaderTest`
Expected: FAIL — `MolitApiItemReader` doesn't exist yet (compile error)

- [ ] **Step 3: Write the reader**

`ingest/src/main/java/com/realestate/ingest/batch/MolitApiItemReader.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.MolitApiClient;
import org.springframework.batch.item.ItemReader;

import java.util.Iterator;
import java.util.List;

public class MolitApiItemReader implements ItemReader<AptTradeItem> {

    private static final int PAGE_SIZE = 1000;

    private final MolitApiClient client;
    private final String regionCode;
    private final String dealYm;

    private int pageNo = 1;
    private Iterator<AptTradeItem> currentPage = List.<AptTradeItem>of().iterator();
    private boolean exhausted = false;

    public MolitApiItemReader(MolitApiClient client, String regionCode, String dealYm) {
        this.client = client;
        this.regionCode = regionCode;
        this.dealYm = dealYm;
    }

    @Override
    public AptTradeItem read() {
        if (!currentPage.hasNext() && !exhausted) {
            List<AptTradeItem> page = client.fetchTrades(regionCode, dealYm, pageNo, PAGE_SIZE);
            if (page.isEmpty()) {
                exhausted = true;
            } else {
                currentPage = page.iterator();
                pageNo++;
            }
        }
        return currentPage.hasNext() ? currentPage.next() : null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests MolitApiItemReaderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/batch/MolitApiItemReader.java ingest/src/test/java/com/realestate/ingest/batch/MolitApiItemReaderTest.java
git commit -m "feat: add paginating MolitApiItemReader"
```

---

### Task 8: Wire the Spring Batch job + integration test

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/batch/RealEstateIngestJobConfig.java`
- Test: `ingest/src/test/java/com/realestate/ingest/batch/RealEstateIngestJobConfigTest.java`

- [ ] **Step 1: Write the failing integration test**

`ingest/src/test/java/com/realestate/ingest/batch/RealEstateIngestJobConfigTest.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.IngestApplication;
import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.FakeMolitApiClient;
import com.realestate.ingest.client.MolitApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest(classes = IngestApplication.class)
@Testcontainers
class RealEstateIngestJobConfigTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        public MolitApiClient molitApiClient() {
            var item1 = new AptTradeItem("11110", "종로구", "A아파트", 84.9, 90000, 2023, 7, 1, 3, 2000);
            var item2 = new AptTradeItem("11110", "종로구", "B아파트", 59.8, 70000, 2023, 7, 2, 10, 2010);
            return new FakeMolitApiClient(Map.of(
                "11110:202307:1", List.of(item1, item2)
            ));
        }
    }

    @Test
    void ingestsTwoTransactionsFromFakeApi() throws Exception {
        var jobParameters = new JobParametersBuilder()
            .addString("regionCode", "11110")
            .addString("dealYm", "202307")
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters();

        var execution = jobLauncherTestUtils.launchJob(jobParameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM real_estate_transaction WHERE deal_ym = 202307", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests RealEstateIngestJobConfigTest`
Expected: FAIL — no `Job` bean named `realEstateIngestJob` exists yet, context fails to load

- [ ] **Step 3: Write the job configuration**

`ingest/src/main/java/com/realestate/ingest/batch/RealEstateIngestJobConfig.java`:
```java
package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.MolitApiClient;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class RealEstateIngestJobConfig {

    @Bean
    public Job realEstateIngestJob(JobRepository jobRepository, Step realEstateIngestStep) {
        return new JobBuilder("realEstateIngestJob", jobRepository)
            .start(realEstateIngestStep)
            .build();
    }

    @Bean
    public Step realEstateIngestStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<AptTradeItem> molitApiItemReader,
            ItemProcessor<AptTradeItem, RealEstateTransaction> realEstateTransactionProcessor,
            ItemWriter<RealEstateTransaction> realEstateTransactionWriter) {
        return new StepBuilder("realEstateIngestStep", jobRepository)
            .<AptTradeItem, RealEstateTransaction>chunk(1000, transactionManager)
            .reader(molitApiItemReader)
            .processor(realEstateTransactionProcessor)
            .writer(realEstateTransactionWriter)
            .build();
    }

    @Bean
    @StepScope
    public ItemReader<AptTradeItem> molitApiItemReader(
            MolitApiClient molitApiClient,
            @Value("#{jobParameters['regionCode']}") String regionCode,
            @Value("#{jobParameters['dealYm']}") String dealYm) {
        return new MolitApiItemReader(molitApiClient, regionCode, dealYm);
    }

    @Bean
    public ItemProcessor<AptTradeItem, RealEstateTransaction> realEstateTransactionProcessor() {
        return new RealEstateTransactionProcessor();
    }

    @Bean
    public ItemWriter<RealEstateTransaction> realEstateTransactionWriter(JdbcTemplate jdbcTemplate) {
        return new RealEstateTransactionWriter(jdbcTemplate);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests RealEstateIngestJobConfigTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/batch/RealEstateIngestJobConfig.java ingest/src/test/java/com/realestate/ingest/batch/RealEstateIngestJobConfigTest.java
git commit -m "feat: wire Spring Batch job for real estate ingestion"
```

---

### Task 9: Real MOLIT API client (XML) + manual verification

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/client/MolitApiResponse.java`
- Create: `ingest/src/main/java/com/realestate/ingest/client/MolitApiClientImpl.java`

**Note:** This client hits a real external API and requires a service key from data.go.kr (RTMSDataSvcAptTradeDev). It's verified manually with `curl` and a one-off run, not with an automated test against the live network — that would make the test suite flaky and dependent on external credentials.

- [ ] **Step 1: Create the XML response mapping**

`ingest/src/main/java/com/realestate/ingest/client/MolitApiResponse.java`:
```java
package com.realestate.ingest.client;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.List;

@JsonRootName("response")
public record MolitApiResponse(Body body) {

    public record Body(
        @JacksonXmlElementWrapper(localName = "items")
        @JacksonXmlProperty(localName = "item")
        List<MolitApiItem> items
    ) {}

    public record MolitApiItem(
        String umdNm,
        String aptNm,
        String excluUseAr,
        String dealAmount,
        String dealYear,
        String dealMonth,
        String dealDay,
        String floor,
        String buildYear
    ) {}
}
```

- [ ] **Step 2: Create the real client implementation**

`ingest/src/main/java/com/realestate/ingest/client/MolitApiClientImpl.java`:
```java
package com.realestate.ingest.client;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class MolitApiClientImpl implements MolitApiClient {

    private final RestClient restClient;
    private final XmlMapper xmlMapper = new XmlMapper();
    private final String serviceKey;

    public MolitApiClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${molit.base-url}") String baseUrl,
            @Value("${molit.service-key}") String serviceKey) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.serviceKey = serviceKey;
    }

    @Override
    public List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows) {
        String xml = restClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/getRTMSDataSvcAptTradeDev")
                .queryParam("serviceKey", serviceKey)
                .queryParam("LAWD_CD", regionCode)
                .queryParam("DEAL_YMD", dealYm)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .build())
            .retrieve()
            .body(String.class);

        try {
            MolitApiResponse response = xmlMapper.readValue(xml, MolitApiResponse.class);
            if (response.body() == null || response.body().items() == null) {
                return List.of();
            }
            return response.body().items().stream()
                .map(item -> toAptTradeItem(item, regionCode))
                .toList();
        } catch (Exception e) {
            throw new IllegalStateException(
                "MOLIT API 응답 파싱 실패: regionCode=" + regionCode + ", dealYm=" + dealYm, e);
        }
    }

    private AptTradeItem toAptTradeItem(MolitApiResponse.MolitApiItem item, String regionCode) {
        // 거래금액은 "250,000" 형태(만원 단위)로 내려옴
        long dealAmount = Long.parseLong(item.dealAmount().replace(",", "").trim());
        return new AptTradeItem(
            regionCode,
            item.umdNm().trim(),
            item.aptNm().trim(),
            Double.parseDouble(item.excluUseAr()),
            dealAmount,
            Integer.parseInt(item.dealYear()),
            Integer.parseInt(item.dealMonth()),
            Integer.parseInt(item.dealDay()),
            item.floor() == null || item.floor().isBlank() ? null : Integer.parseInt(item.floor().trim()),
            item.buildYear() == null || item.buildYear().isBlank() ? null : Integer.parseInt(item.buildYear().trim())
        );
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :ingest:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manually verify against the real API**

Get a service key from https://www.data.go.kr (search "아파트매매 실거래자료"), then:

```bash
export MOLIT_SERVICE_KEY="<발급받은 키>"
curl "https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev?serviceKey=$MOLIT_SERVICE_KEY&LAWD_CD=11110&DEAL_YMD=202307&numOfRows=5"
```

Expected: XML response with `<resultCode>000</resultCode>` and up to 5 `<item>` elements containing `aptNm`, `dealAmount`, `umdNm`, etc.

- [ ] **Step 5: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/client/MolitApiResponse.java ingest/src/main/java/com/realestate/ingest/client/MolitApiClientImpl.java
git commit -m "feat: add real MOLIT API client (XML)"
```

---

### Task 10: CommandLineRunner to sweep region×month combinations

**Files:**
- Create: `ingest/src/main/java/com/realestate/ingest/IngestProperties.java`
- Create: `ingest/src/main/java/com/realestate/ingest/IngestRunner.java`
- Test: `ingest/src/test/java/com/realestate/ingest/IngestRunnerTest.java`

- [ ] **Step 1: Write the failing test**

`ingest/src/test/java/com/realestate/ingest/IngestRunnerTest.java`:
```java
package com.realestate.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestRunnerTest {

    @Test
    void buildsJobParametersForEveryRegionAndMonthCombination() {
        var properties = new IngestProperties(
            List.of("11110", "11140"),
            List.of("202301", "202302"));
        var runner = new IngestRunner(mock(JobLauncher.class), null, properties);

        List<JobParameters> result = runner.buildAllJobParameters();

        assertThat(result).hasSize(4);
        assertThat(result).extracting(p -> p.getString("regionCode"))
            .containsExactly("11110", "11110", "11140", "11140");
        assertThat(result).extracting(p -> p.getString("dealYm"))
            .containsExactly("202301", "202302", "202301", "202302");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ingest:test --tests IngestRunnerTest`
Expected: FAIL — `IngestProperties` and `IngestRunner` don't exist yet (compile error)

- [ ] **Step 3: Write the properties record**

`ingest/src/main/java/com/realestate/ingest/IngestProperties.java`:
```java
package com.realestate.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(List<String> regionCodes, List<String> dealYms) {}
```

- [ ] **Step 4: Write the runner**

`ingest/src/main/java/com/realestate/ingest/IngestRunner.java`:
```java
package com.realestate.ingest;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ingest.run-on-startup", havingValue = "true")
public class IngestRunner implements CommandLineRunner {

    private final JobLauncher jobLauncher;
    private final Job realEstateIngestJob;
    private final IngestProperties properties;

    public IngestRunner(JobLauncher jobLauncher, Job realEstateIngestJob, IngestProperties properties) {
        this.jobLauncher = jobLauncher;
        this.realEstateIngestJob = realEstateIngestJob;
        this.properties = properties;
    }

    @Override
    public void run(String... args) throws Exception {
        for (JobParameters params : buildAllJobParameters()) {
            jobLauncher.run(realEstateIngestJob, params);
        }
    }

    public List<JobParameters> buildAllJobParameters() {
        return properties.regionCodes().stream()
            .flatMap(region -> properties.dealYms().stream()
                .map(ym -> new JobParametersBuilder()
                    .addString("regionCode", region)
                    .addString("dealYm", ym)
                    .toJobParameters()))
            .toList();
    }
}
```

- [ ] **Step 5: Enable configuration properties scanning**

Modify `ingest/src/main/java/com/realestate/ingest/IngestApplication.java`:
```java
package com.realestate.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IngestProperties.class)
public class IngestApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :ingest:test --tests IngestRunnerTest`
Expected: PASS

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew :ingest:test`
Expected: all tests PASS (`SchemaMigrationTest`, `RealEstateTransactionProcessorTest`, `RealEstateTransactionWriterTest`, `MolitApiItemReaderTest`, `RealEstateIngestJobConfigTest`, `IngestRunnerTest`)

- [ ] **Step 8: Commit**

```bash
git add ingest/src/main/java/com/realestate/ingest/IngestProperties.java ingest/src/main/java/com/realestate/ingest/IngestRunner.java ingest/src/main/java/com/realestate/ingest/IngestApplication.java ingest/src/test/java/com/realestate/ingest/IngestRunnerTest.java
git commit -m "feat: add CommandLineRunner sweeping region x month combinations"
```

---

## Manual end-to-end verification (after Task 10)

1. `docker compose up -d` (MariaDB + Redis running)
2. Set `MOLIT_SERVICE_KEY` env var to a real service key
3. Edit `application.yml` — set `ingest.run-on-startup: true`, adjust `region-codes`/`deal-yms` to a small real range (e.g. one region, one month) for a first smoke run
4. Run: `./gradlew :ingest:bootRun`
5. Verify: `docker exec -it realestate-analytics-mariadb-1 mariadb -urealestate -prealestate realestate -e "SELECT COUNT(*) FROM real_estate_transaction"` returns a non-zero count matching real transaction data for that region/month
6. Set `ingest.run-on-startup` back to `false` before committing (startup ingestion should stay opt-in)
