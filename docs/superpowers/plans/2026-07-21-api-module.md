# API Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Note on this project:** For realestate-analytics specifically, the human partner wants to write/pair on the code directly rather than have it built autonomously by subagents — see the discussion that produced this plan. Treat this plan as a shared design reference to implement together in-session, not as a queue to dispatch to background implementer subagents.

**Goal:** Stand up the `api` module — a read-only Spring Boot service that searches real estate transactions (region + period + area, keyset-paginated) and serves cached region/month price statistics, reading from the `real_estate_transaction` table the `ingest` module populates.

**Architecture:** New Gradle submodule `api`, sibling to `ingest`. JPA entity `RealEstateTransaction` mapped read-mostly onto the existing partitioned table (`@Id` on `id` alone — `deal_ym` is a partition-key artifact of the table's DDL, not something the ORM needs to model as part of a composite key). QueryDSL builds the dynamic search predicate; keyset (seek) pagination sorts by `(deal_ym DESC, id DESC)` with a base64-encoded cursor. Region×month aggregate stats (avg price, transaction count) are cached in Redis with a TTL, keyed by `regionCode:dealYm`. `api` never runs Flyway migrations against the real database (schema ownership stays with `ingest`); its Testcontainers tests bootstrap the same DDL via a test-only `schema.sql`.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Spring Data JPA, QueryDSL 5.x (jakarta), Spring Data Redis, MariaDB 11.4, Testcontainers (MariaDB + generic Redis container), JUnit 5, AssertJ.

**Note on scope:** This is Plan 2 of 4 for the realestate-analytics project (see `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`). `web` (Vue3) and the benchmark scripts are separate follow-up plans.

---

### Design decisions locked in during planning discussion

| Decision | Choice | Reason |
|---|---|---|
| JPA `@Id` | `id` column alone, `dealYm` as a plain field | `id` is `AUTO_INCREMENT` and already globally unique; the composite `PRIMARY KEY (id, deal_ym)` in the DDL exists only to satisfy MariaDB's "partition key must be part of every unique key" rule, not an application-level identity concern. Modeling a composite `@IdClass` would add ceremony with no benefit here. |
| Pagination cursor | `(deal_ym DESC, id DESC)`, base64-encoded `"<dealYm>:<id>"` | Matches the existing `idx_region_ym (region_code, deal_ym)` index and the natural "most recent transactions first" browsing pattern. |
| Cached aggregate | avg price + count, keyed by `regionCode:dealYm` | One cache entry per region×month keeps invalidation/TTL reasoning simple; re-aggregating per month is cheap enough that a rolling N-month list isn't worth the extra complexity. |
| Schema ownership in tests | `api` test resources ship their own `schema.sql` (copied DDL, no Flyway dependency in `api`) | `api` is read-only in production and shouldn't own migrations; Spring Boot's test-only `schema.sql` auto-init is the standard way to stand up a schema for an integration test without pulling in Flyway. |

---

### Task 1: `api` Gradle module scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Create: `api/build.gradle.kts`
- Create: `api/src/main/java/com/realestate/api/ApiApplication.java`

- [ ] **Step 1: Add the module to settings**

`settings.gradle.kts`:
```kotlin
rootProject.name = "realestate-analytics"
include("ingest")
include("api")
```

- [ ] **Step 2: Create the api module build file**

`api/build.gradle.kts`:
```kotlin
plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    java
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.mariadb.jdbc:mariadb-java-client")
}
```

**Note for implementer:** verify the exact `querydsl-jpa`/`querydsl-apt` version resolves against Spring Boot 3.3.4's dependency-management BOM before locking it in — if the BOM already manages a QueryDSL version, drop the explicit `:5.1.0` and let it be managed. Confirm with `./gradlew :api:dependencies --configuration compileClasspath | grep querydsl`.

- [ ] **Step 3: Create the Spring Boot application entry point**

`api/src/main/java/com/realestate/api/ApiApplication.java`:
```java
package com.realestate.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
```

- [ ] **Step 4: Verify the build**

Run: `./gradlew :api:build -x test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts api/build.gradle.kts api/src
git commit -m "chore: scaffold api module"
```

---

### Task 2: `RealEstateTransaction` JPA entity + repository skeleton

**Files:**
- Create: `api/src/main/java/com/realestate/api/domain/RealEstateTransaction.java`
- Create: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionRepository.java`
- Create: `api/src/main/resources/application.yml`
- Create: `api/src/test/resources/schema.sql`
- Create: `api/src/test/resources/application.yml`
- Test: `api/src/test/java/com/realestate/api/domain/RealEstateTransactionRepositoryTest.java`

- [ ] **Step 1: Write the test schema (same DDL as ingest's Flyway V1, no Flyway dependency needed here)**

`api/src/test/resources/schema.sql`:
```sql
CREATE TABLE IF NOT EXISTS real_estate_transaction (
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

`api/src/test/resources/application.yml`:
```yaml
spring:
  sql:
    init:
      mode: always
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
```

- [ ] **Step 2: Write the failing test**

`api/src/test/java/com/realestate/api/domain/RealEstateTransactionRepositoryTest.java`:
```java
package com.realestate.api.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RealEstateTransactionRepositoryTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RealEstateTransactionRepository repository;

    @Test
    void findsPersistedTransactionById() {
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount,
                 deal_year, deal_month, deal_day, deal_ym, floor, build_year)
            VALUES ('11110', '종로구', '테스트아파트', 84.95, 95000, 2023, 7, 15, 202307, 5, 2005)
            """);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM real_estate_transaction", Long.class);

        RealEstateTransaction found = repository.findById(id).orElseThrow();

        assertThat(found.getAptName()).isEqualTo("테스트아파트");
        assertThat(found.getDealAmount()).isEqualTo(95000L);
        assertThat(found.getExclusiveArea()).isEqualByComparingTo(BigDecimal.valueOf(84.95));
        assertThat(found.getDealYm()).isEqualTo(202307);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :api:test --tests RealEstateTransactionRepositoryTest`
Expected: FAIL — `RealEstateTransaction`/`RealEstateTransactionRepository` don't exist yet (compile error)

- [ ] **Step 4: Write the entity**

`api/src/main/java/com/realestate/api/domain/RealEstateTransaction.java`:
```java
package com.realestate.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "real_estate_transaction")
public class RealEstateTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_code", nullable = false)
    private String regionCode;

    @Column(name = "legal_dong", nullable = false)
    private String legalDong;

    @Column(name = "apt_name", nullable = false)
    private String aptName;

    @Column(name = "exclusive_area", nullable = false)
    private BigDecimal exclusiveArea;

    @Column(name = "deal_amount", nullable = false)
    private Long dealAmount;

    @Column(name = "deal_year", nullable = false)
    private Integer dealYear;

    @Column(name = "deal_month", nullable = false)
    private Integer dealMonth;

    @Column(name = "deal_day", nullable = false)
    private Integer dealDay;

    @Column(name = "deal_ym", nullable = false)
    private Integer dealYm;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "build_year")
    private Integer buildYear;

    protected RealEstateTransaction() {
    }

    public Long getId() { return id; }
    public String getRegionCode() { return regionCode; }
    public String getLegalDong() { return legalDong; }
    public String getAptName() { return aptName; }
    public BigDecimal getExclusiveArea() { return exclusiveArea; }
    public Long getDealAmount() { return dealAmount; }
    public Integer getDealYear() { return dealYear; }
    public Integer getDealMonth() { return dealMonth; }
    public Integer getDealDay() { return dealDay; }
    public Integer getDealYm() { return dealYm; }
    public Integer getFloor() { return floor; }
    public Integer getBuildYear() { return buildYear; }
}
```

`api/src/main/java/com/realestate/api/domain/RealEstateTransactionRepository.java`:
```java
package com.realestate.api.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RealEstateTransactionRepository
        extends JpaRepository<RealEstateTransaction, Long>, RealEstateTransactionQueryRepository {
}
```

**Note for implementer:** `RealEstateTransactionQueryRepository` (the QueryDSL custom interface) doesn't exist yet — that's Task 3. For this task, temporarily drop the `RealEstateTransactionQueryRepository` extension (just `extends JpaRepository<RealEstateTransaction, Long>`) so it compiles standalone; Task 3 adds the extension back in.

`api/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mariadb://localhost:13306/realestate
    username: realestate
    password: realestate
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :api:test --tests RealEstateTransactionRepositoryTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/realestate/api/domain api/src/main/resources/application.yml api/src/test/resources api/src/test/java/com/realestate/api/domain/RealEstateTransactionRepositoryTest.java
git commit -m "feat: add RealEstateTransaction JPA entity and repository"
```

---

### Task 3: QueryDSL dynamic search + keyset pagination

**Files:**
- Create: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java`
- Create: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java`
- Create: `api/src/main/java/com/realestate/api/domain/TransactionSearchCondition.java`
- Create: `api/src/main/java/com/realestate/api/domain/TransactionCursor.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionRepository.java`
- Test: `api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java`

**Design:** `TransactionSearchCondition` carries the optional filters (`regionCode`, `dealYmFrom`, `dealYmTo`, `minArea`, `maxArea`). `TransactionCursor` is a small record (`dealYm`, `id`) with `encode()`/`decode(String)` for the base64 cursor. The QueryDSL implementation builds a `BooleanBuilder` from non-null condition fields, adds the keyset predicate (`dealYm < cursor.dealYm() OR (dealYm = cursor.dealYm() AND id < cursor.id())`) when a cursor is present, and orders by `dealYm DESC, id DESC` with `LIMIT size + 1` (fetch one extra row to know if there's a next page without a separate count query).

- [ ] **Step 1: Write the cursor type and its test**

`api/src/main/java/com/realestate/api/domain/TransactionCursor.java`:
```java
package com.realestate.api.domain;

import java.util.Base64;

public record TransactionCursor(int dealYm, long id) {

    public String encode() {
        String raw = dealYm + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    public static TransactionCursor decode(String encoded) {
        String raw = new String(Base64.getUrlDecoder().decode(encoded));
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor: " + encoded);
        }
        try {
            return new TransactionCursor(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor: " + encoded, e);
        }
    }
}
```

`api/src/test/java/com/realestate/api/domain/TransactionCursorTest.java`:
```java
package com.realestate.api.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionCursorTest {

    @Test
    void roundTripsThroughEncodeAndDecode() {
        var cursor = new TransactionCursor(202307, 42L);

        var decoded = TransactionCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void rejectsGarbageInput() {
        assertThatThrownBy(() -> TransactionCursor.decode("not-valid-base64!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the cursor test**

Run: `./gradlew :api:test --tests TransactionCursorTest`
Expected: PASS (this class has no dependency on Spring/QueryDSL, so it should compile and pass immediately)

- [ ] **Step 3: Write the search condition type**

`api/src/main/java/com/realestate/api/domain/TransactionSearchCondition.java`:
```java
package com.realestate.api.domain;

import java.math.BigDecimal;

public record TransactionSearchCondition(
    String regionCode,
    Integer dealYmFrom,
    Integer dealYmTo,
    BigDecimal minArea,
    BigDecimal maxArea
) {}
```

- [ ] **Step 4: Write the failing integration test for the query repository**

`api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java`:
```java
package com.realestate.api.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RealEstateTransactionQueryRepositoryImpl.class)
class RealEstateTransactionQueryRepositoryTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RealEstateTransactionQueryRepository queryRepository;

    @BeforeEach
    void seed() {
        // 3 rows in region 11110, deal_ym 202305/202306/202307; 1 row in a different region
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES
                ('11110', '종로구', 'A', 84.9, 90000, 2023, 5, 1, 202305),
                ('11110', '종로구', 'B', 59.8, 70000, 2023, 6, 1, 202306),
                ('11110', '종로구', 'C', 59.8, 75000, 2023, 7, 1, 202307),
                ('11140', '중구', 'D', 59.8, 80000, 2023, 7, 1, 202307)
            """);
    }

    @Test
    void filtersByRegionAndReturnsNewestFirst() {
        var condition = new TransactionSearchCondition("11110", null, null, null, null);

        List<RealEstateTransaction> result = queryRepository.search(condition, null, 10);

        assertThat(result).extracting(RealEstateTransaction::getAptName)
            .containsExactly("C", "B", "A");
    }

    @Test
    void appliesKeysetCursorToSkipAlreadySeenRows() {
        var condition = new TransactionSearchCondition("11110", null, null, null, null);
        var cursorAtC = new TransactionCursor(202307, idOf("C"));

        List<RealEstateTransaction> result = queryRepository.search(condition, cursorAtC, 10);

        assertThat(result).extracting(RealEstateTransaction::getAptName)
            .containsExactly("B", "A");
    }

    private Long idOf(String aptName) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM real_estate_transaction WHERE apt_name = ?", Long.class, aptName);
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: FAIL — `RealEstateTransactionQueryRepository`/`RealEstateTransactionQueryRepositoryImpl` don't exist yet (compile error)

- [ ] **Step 6: Write the query repository interface and QueryDSL implementation**

`api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java`:
```java
package com.realestate.api.domain;

import java.util.List;

public interface RealEstateTransactionQueryRepository {
    List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size);
}
```

`api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java`:
```java
package com.realestate.api.domain;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.util.List;

import static com.realestate.api.domain.QRealEstateTransaction.realEstateTransaction;

public class RealEstateTransactionQueryRepositoryImpl implements RealEstateTransactionQueryRepository {

    private final JPAQueryFactory queryFactory;

    public RealEstateTransactionQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size) {
        var q = realEstateTransaction;
        var predicate = new BooleanBuilder();

        if (condition.regionCode() != null) {
            predicate.and(q.regionCode.eq(condition.regionCode()));
        }
        if (condition.dealYmFrom() != null) {
            predicate.and(q.dealYm.goe(condition.dealYmFrom()));
        }
        if (condition.dealYmTo() != null) {
            predicate.and(q.dealYm.loe(condition.dealYmTo()));
        }
        if (condition.minArea() != null) {
            predicate.and(q.exclusiveArea.goe(condition.minArea()));
        }
        if (condition.maxArea() != null) {
            predicate.and(q.exclusiveArea.loe(condition.maxArea()));
        }
        if (cursor != null) {
            predicate.and(
                q.dealYm.lt(cursor.dealYm())
                    .or(q.dealYm.eq(cursor.dealYm()).and(q.id.lt(cursor.id())))
            );
        }

        return queryFactory.selectFrom(q)
            .where(predicate)
            .orderBy(q.dealYm.desc(), q.id.desc())
            .limit(size)
            .fetch();
    }
}
```

**Note for implementer:** `QRealEstateTransaction` is QueryDSL's generated Q-type from the `@Entity` annotation processor configured in Task 1 — it won't exist until you run a build (`./gradlew :api:compileJava`) that triggers annotation processing. If it's not found, check that `annotationProcessor("com.querydsl:querydsl-apt:...:jakarta")` actually ran (look under `api/build/generated/sources/annotationProcessor`).

- [ ] **Step 7: Wire the query repository into the main repository interface**

`api/src/main/java/com/realestate/api/domain/RealEstateTransactionRepository.java` (revert the Task 2 workaround):
```java
package com.realestate.api.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RealEstateTransactionRepository
        extends JpaRepository<RealEstateTransaction, Long>, RealEstateTransactionQueryRepository {
}
```

Spring Data JPA will look for a bean named `RealEstateTransactionRepositoryImpl` combining the base repository, but since the custom part is `RealEstateTransactionQueryRepository`, name the impl class `RealEstateTransactionQueryRepositoryImpl` and rely on Spring Data's default suffix-matching (`<QueryRepositoryInterfaceName>Impl`) — confirm this resolves; if Spring Data can't find it, an explicit `@Component` on the impl class plus constructor injection of `EntityManager` is the fallback.

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: PASS

- [ ] **Step 9: Run the full module suite**

Run: `./gradlew :api:test`
Expected: all tests PASS (`TransactionCursorTest`, `RealEstateTransactionRepositoryTest`, `RealEstateTransactionQueryRepositoryTest`)

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/com/realestate/api/domain api/src/test/java/com/realestate/api/domain
git commit -m "feat: add QueryDSL dynamic search with keyset pagination"
```

---

### Task 4: Search REST API (controller + DTOs)

**Files:**
- Create: `api/src/main/java/com/realestate/api/web/TransactionSearchController.java`
- Create: `api/src/main/java/com/realestate/api/web/TransactionResponse.java`
- Create: `api/src/main/java/com/realestate/api/web/TransactionSearchResponse.java`
- Test: `api/src/test/java/com/realestate/api/web/TransactionSearchControllerTest.java`

**Design:** `GET /api/transactions?regionCode=11110&dealYmFrom=202301&dealYmTo=202312&minArea=59&maxArea=85&cursor=<opaque>&size=20`. Response is `{ "items": [...], "nextCursor": "<opaque-or-null>" }`. The controller fetches `size + 1` rows from the repository; if it got `size + 1` back, there's a next page — compute `nextCursor` from the last item in the trimmed `size`-length list, and drop the extra row before returning.

- [ ] **Step 1: Write the failing controller test**

`api/src/test/java/com/realestate/api/web/TransactionSearchControllerTest.java`:
```java
package com.realestate.api.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class TransactionSearchControllerTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', '테스트아파트', 84.9, 90000, 2023, 7, 1, 202307)
            """);
    }

    @Test
    void searchesByRegionCodeAndReturnsMatchingItem() throws Exception {
        mockMvc.perform(get("/api/transactions").param("regionCode", "11110").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.items[0].aptName").value("테스트아파트"))
            .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
```

**Note for implementer:** this test needs the same `schema.sql`/test `application.yml` from Task 2 to be on the test classpath — confirm `src/test/resources` is shared correctly across the module (it should be, Gradle's default source set layout).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :api:test --tests TransactionSearchControllerTest`
Expected: FAIL — no mapping for `/api/transactions` (404), or compile error if DTOs referenced don't exist yet

- [ ] **Step 3: Write the response DTOs**

`api/src/main/java/com/realestate/api/web/TransactionResponse.java`:
```java
package com.realestate.api.web;

import com.realestate.api.domain.RealEstateTransaction;

import java.math.BigDecimal;

public record TransactionResponse(
    Long id,
    String regionCode,
    String legalDong,
    String aptName,
    BigDecimal exclusiveArea,
    Long dealAmount,
    Integer dealYm,
    Integer floor,
    Integer buildYear
) {
    public static TransactionResponse from(RealEstateTransaction tx) {
        return new TransactionResponse(
            tx.getId(), tx.getRegionCode(), tx.getLegalDong(), tx.getAptName(),
            tx.getExclusiveArea(), tx.getDealAmount(), tx.getDealYm(), tx.getFloor(), tx.getBuildYear());
    }
}
```

`api/src/main/java/com/realestate/api/web/TransactionSearchResponse.java`:
```java
package com.realestate.api.web;

import java.util.List;

public record TransactionSearchResponse(List<TransactionResponse> items, String nextCursor) {}
```

- [ ] **Step 4: Write the controller**

`api/src/main/java/com/realestate/api/web/TransactionSearchController.java`:
```java
package com.realestate.api.web;

import com.realestate.api.domain.RealEstateTransaction;
import com.realestate.api.domain.RealEstateTransactionRepository;
import com.realestate.api.domain.TransactionCursor;
import com.realestate.api.domain.TransactionSearchCondition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
public class TransactionSearchController {

    private final RealEstateTransactionRepository repository;

    public TransactionSearchController(RealEstateTransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/transactions")
    public TransactionSearchResponse search(
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Integer dealYmFrom,
            @RequestParam(required = false) Integer dealYmTo,
            @RequestParam(required = false) BigDecimal minArea,
            @RequestParam(required = false) BigDecimal maxArea,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        var condition = new TransactionSearchCondition(regionCode, dealYmFrom, dealYmTo, minArea, maxArea);
        var decodedCursor = cursor != null ? TransactionCursor.decode(cursor) : null;

        List<RealEstateTransaction> fetched = repository.search(condition, decodedCursor, size + 1);

        boolean hasNext = fetched.size() > size;
        List<RealEstateTransaction> page = hasNext ? fetched.subList(0, size) : fetched;

        String nextCursor = hasNext
            ? new TransactionCursor(page.get(page.size() - 1).getDealYm(), page.get(page.size() - 1).getId()).encode()
            : null;

        return new TransactionSearchResponse(page.stream().map(TransactionResponse::from).toList(), nextCursor);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :api:test --tests TransactionSearchControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/realestate/api/web api/src/test/java/com/realestate/api/web
git commit -m "feat: add transaction search REST endpoint"
```

---

### Task 5: Region×month statistics query

**Files:**
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java`
- Create: `api/src/main/java/com/realestate/api/domain/RegionStats.java`
- Test: extend `RealEstateTransactionQueryRepositoryTest.java`

**Design:** `RegionStats(long count, BigDecimal avgDealAmount)` — a QueryDSL projection using `avg()`/`count()` filtered by `regionCode` + exact `dealYm`. Returns `RegionStats(0, null)` (or a documented sentinel) when there's no data for that region/month — decide in-session whether `null` avg or a zero-value record reads better in the JSON response before wiring the controller in Task 6.

- [ ] **Step 1: Add a failing test case to the existing query repository test**

Add to `RealEstateTransactionQueryRepositoryTest.java`:
```java
@Test
void aggregatesAveragePriceAndCountForRegionAndMonth() {
    RegionStats stats = queryRepository.statsFor("11110", 202307);

    assertThat(stats.count()).isEqualTo(1);
    assertThat(stats.avgDealAmount()).isEqualByComparingTo(java.math.BigDecimal.valueOf(75000));
}

@Test
void returnsZeroCountWhenNoDataForRegionAndMonth() {
    RegionStats stats = queryRepository.statsFor("11110", 202312);

    assertThat(stats.count()).isZero();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: FAIL — `RegionStats`/`statsFor` don't exist yet

- [ ] **Step 3: Write `RegionStats` and the query method**

`api/src/main/java/com/realestate/api/domain/RegionStats.java`:
```java
package com.realestate.api.domain;

import java.math.BigDecimal;

public record RegionStats(long count, BigDecimal avgDealAmount) {}
```

Add to `RealEstateTransactionQueryRepository.java`:
```java
RegionStats statsFor(String regionCode, int dealYm);
```

Add to `RealEstateTransactionQueryRepositoryImpl.java`:
```java
@Override
public RegionStats statsFor(String regionCode, int dealYm) {
    var q = realEstateTransaction;
    Long count = queryFactory.select(q.count())
        .from(q)
        .where(q.regionCode.eq(regionCode), q.dealYm.eq(dealYm))
        .fetchOne();
    Double avg = queryFactory.select(q.dealAmount.avg())
        .from(q)
        .where(q.regionCode.eq(regionCode), q.dealYm.eq(dealYm))
        .fetchOne();

    long safeCount = count != null ? count : 0L;
    BigDecimal safeAvg = (avg != null) ? java.math.BigDecimal.valueOf(avg) : java.math.BigDecimal.ZERO;
    return new RegionStats(safeCount, safeAvg);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/realestate/api/domain/RegionStats.java api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java
git commit -m "feat: add region x month price/volume aggregation query"
```

---

### Task 6: Redis-cached region stats endpoint

**Files:**
- Create: `api/src/main/java/com/realestate/api/config/CacheConfig.java`
- Create: `api/src/main/java/com/realestate/api/domain/RegionStatsService.java`
- Create: `api/src/main/java/com/realestate/api/web/RegionStatsController.java`
- Create: `api/src/main/java/com/realestate/api/web/RegionStatsResponse.java`
- Modify: `api/build.gradle.kts` (test-only Redis Testcontainers dependency)
- Test: `api/src/test/java/com/realestate/api/domain/RegionStatsServiceTest.java`

**Design:** `RegionStatsService.getStats(regionCode, dealYm)` wraps `queryRepository.statsFor(...)` with `@Cacheable("regionStats")`, keyed by a `SimpleKey`-equivalent of `regionCode + ":" + dealYm`. `CacheConfig` sets a 1-hour TTL on the `regionStats` cache via `RedisCacheManager`. The controller just calls the service and maps to a response DTO — no caching logic in the web layer.

- [ ] **Step 1: Add a Redis Testcontainer dependency for tests**

Add to `api/build.gradle.kts` `dependencies` block:
```kotlin
testImplementation("org.testcontainers:testcontainers:2.0.5")
```

(Use the generic `GenericContainer`/`org.testcontainers.containers.GenericContainer` with the `redis:7.4` image rather than a Redis-specific Testcontainers module — confirm during implementation whether Testcontainers 2.x ships a dedicated `testcontainers-redis` module; if so, prefer it, otherwise `GenericContainer` works fine.)

- [ ] **Step 2: Write the failing service test**

`api/src/test/java/com/realestate/api/domain/RegionStatsServiceTest.java`:
```java
package com.realestate.api.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class RegionStatsServiceTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegionStatsService regionStatsService;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'A', 84.9, 90000, 2023, 7, 1, 202307)
            """);
    }

    @Test
    void cachesStatsAcrossRepeatedCalls() {
        RegionStats first = regionStatsService.getStats("11110", 202307);
        // second insert should NOT be reflected if caching is working
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'B', 59.8, 70000, 2023, 7, 2, 202307)
            """);
        RegionStats second = regionStatsService.getStats("11110", 202307);

        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isEqualTo(1); // still 1 — cached, not re-queried
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :api:test --tests RegionStatsServiceTest`
Expected: FAIL — `RegionStatsService` doesn't exist (compile error)

- [ ] **Step 4: Write `CacheConfig` and `RegionStatsService`**

`api/src/main/java/com/realestate/api/config/CacheConfig.java`:
```java
package com.realestate.api.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(1))
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}
```

`api/src/main/java/com/realestate/api/domain/RegionStatsService.java`:
```java
package com.realestate.api.domain;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class RegionStatsService {

    private final RealEstateTransactionRepository repository;

    public RegionStatsService(RealEstateTransactionRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "regionStats", key = "#regionCode + ':' + #dealYm")
    public RegionStats getStats(String regionCode, int dealYm) {
        return repository.statsFor(regionCode, dealYm);
    }
}
```

**Note for implementer:** `RegionStats` isn't natively JSON-serializable by the default Redis value serializer unless `RedisCacheConfiguration` also gets a value serializer configured (default is JDK serialization, which requires `RegionStats` — a record — to work with it, or you configure a `GenericJackson2JsonRedisSerializer` for values). Verify this empirically: if the cache test fails with a serialization error, add `.serializeValuesWith(...)` with a Jackson serializer to `CacheConfig`.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :api:test --tests RegionStatsServiceTest`
Expected: PASS

- [ ] **Step 6: Write the controller**

`api/src/main/java/com/realestate/api/web/RegionStatsResponse.java`:
```java
package com.realestate.api.web;

import com.realestate.api.domain.RegionStats;

import java.math.BigDecimal;

public record RegionStatsResponse(String regionCode, int dealYm, long count, BigDecimal avgDealAmount) {
    public static RegionStatsResponse of(String regionCode, int dealYm, RegionStats stats) {
        return new RegionStatsResponse(regionCode, dealYm, stats.count(), stats.avgDealAmount());
    }
}
```

`api/src/main/java/com/realestate/api/web/RegionStatsController.java`:
```java
package com.realestate.api.web;

import com.realestate.api.domain.RegionStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionStatsController {

    private final RegionStatsService regionStatsService;

    public RegionStatsController(RegionStatsService regionStatsService) {
        this.regionStatsService = regionStatsService;
    }

    @GetMapping("/api/regions/{regionCode}/stats")
    public RegionStatsResponse stats(@PathVariable String regionCode, @RequestParam int dealYm) {
        return RegionStatsResponse.of(regionCode, dealYm, regionStatsService.getStats(regionCode, dealYm));
    }
}
```

- [ ] **Step 7: Run the full module suite**

Run: `./gradlew :api:test`
Expected: all tests PASS

- [ ] **Step 8: Commit**

```bash
git add api/build.gradle.kts api/src/main/java/com/realestate/api/config api/src/main/java/com/realestate/api/domain/RegionStatsService.java api/src/main/java/com/realestate/api/web/RegionStatsController.java api/src/main/java/com/realestate/api/web/RegionStatsResponse.java api/src/test/java/com/realestate/api/domain/RegionStatsServiceTest.java
git commit -m "feat: add Redis-cached region stats endpoint"
```

---

### Task 7: Global exception handling

**Files:**
- Create: `api/src/main/java/com/realestate/api/web/GlobalExceptionHandler.java`
- Create: `api/src/main/java/com/realestate/api/web/ErrorResponse.java`
- Test: extend `TransactionSearchControllerTest.java`

**Design:** `IllegalArgumentException` (thrown by `TransactionCursor.decode` on a malformed cursor) maps to `400 Bad Request`. Anything unhandled maps to `500` with a generic message (never leak the raw exception message to the client for unhandled cases — but for `IllegalArgumentException`, the message is safe/expected to show since it's caller error, not an internal detail).

- [ ] **Step 1: Add a failing test case**

Add to `TransactionSearchControllerTest.java`:
```java
@Test
void returnsBadRequestForMalformedCursor() throws Exception {
    mockMvc.perform(get("/api/transactions").param("cursor", "not-valid-base64!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").exists());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api:test --tests TransactionSearchControllerTest`
Expected: FAIL — currently an uncaught `IllegalArgumentException` surfaces as `500`, not `400`

- [ ] **Step 3: Write the error response type and handler**

`api/src/main/java/com/realestate/api/web/ErrorResponse.java`:
```java
package com.realestate.api.web;

public record ErrorResponse(String message) {}
```

`api/src/main/java/com/realestate/api/web/GlobalExceptionHandler.java`:
```java
package com.realestate.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Unexpected error"));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :api:test --tests TransactionSearchControllerTest`
Expected: PASS

- [ ] **Step 5: Run the full module suite**

Run: `./gradlew :api:test`
Expected: all tests PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/realestate/api/web/GlobalExceptionHandler.java api/src/main/java/com/realestate/api/web/ErrorResponse.java api/src/test/java/com/realestate/api/web/TransactionSearchControllerTest.java
git commit -m "feat: add global exception handler for bad requests"
```

---

## Manual end-to-end verification (after Task 7)

1. `docker compose up -d` (MariaDB + Redis running, same as `ingest`)
2. Run the `ingest` job against a small real range so `real_estate_transaction` has data (see `ingest` plan's manual verification section)
3. Run: `./gradlew :api:bootRun`
4. `curl "http://localhost:8080/api/transactions?regionCode=11110&size=5"` — expect a JSON list of transactions
5. `curl "http://localhost:8080/api/regions/11110/stats?dealYm=202307"` — expect `{"regionCode":"11110","dealYm":202307,"count":...,"avgDealAmount":...}`
6. Call the stats endpoint twice, confirm via `redis-cli` (`KEYS *`, `TTL <key>`) that a cache entry exists with a ~1 hour TTL
