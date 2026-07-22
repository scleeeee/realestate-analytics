# Benchmark Scripts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Note on this project:** For realestate-analytics specifically, the human partner wants to write/pair on the code directly rather than have it built autonomously by subagents — see `docs/superpowers/plans/2026-07-21-api-module.md` and the design discussion that produced this plan. Treat this plan as a shared design reference to implement together in-session, not as a queue to dispatch to background implementer subagents.

**Goal:** Prove out the project's core selling point — measured performance impact of indexing, partitioning, and keyset vs. offset pagination — with Node.js scripts that seed a large synthetic dataset, toggle each optimization on/off via DDL, and report p50/p95/avg response times as a markdown table. Also adds the offset-pagination endpoint on `api` that the third comparison needs.

**Architecture:** `benchmark/` is a standalone Node.js project (not a Gradle module), living in a new `task/benchmark` branch/worktree cut from `task/web`'s tip. It talks to MariaDB directly via `mysql2` (for seeding and DDL toggles) and to the running `api` server via `fetch` (for the actual timed requests — the point is to measure what a real client experiences, not raw SQL execution time). Three independent scenario scripts share a common sequential-timing helper (`measure.js`) and DDL toggle helper (`toggle.js`), and each scenario restores the schema to its "production" state (indexed + partitioned) when it finishes.

**Tech Stack:** Node.js (built-in `fetch`, ES modules), `mysql2` for MariaDB access. No test framework — per the design doc, benchmark scripts are documented via their own output, not covered by an automated test suite; each script is instead verified by direct execution.

**Note on scope:** This is Plan 4 of 4 for the realestate-analytics project (see `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`). It's the last plan in the roadmap.

---

### Design decisions locked in during planning discussion

| Decision | Choice | Reason |
|---|---|---|
| Benchmark tooling | Node.js scripts (`mysql2` + built-in `fetch`) | Reuses the Node/npm environment already set up for `web`; no separate JVM+GUI tool (JMeter) install. What matters for the portfolio story is the measured result, not the tool. |
| Benchmark-scale data | Synthetic data generated via `mysql2` batched INSERT (5,000,000 rows) | This environment has no `MOLIT_SERVICE_KEY`, so real ingestion can't reach benchmark scale. A configurable row count (default 5M, override via CLI arg) lets smaller runs be used while developing/verifying the scripts. |
| offset-vs-keyset comparison endpoint | New `GET /api/transactions/offset` on `api`, committed onto the existing `task/api-module` branch (PR #2) | Mirrors how the `web` plan's range-stats endpoint was added — keeps all `api` changes in one PR. `api` only had keyset pagination before this. |
| Index/partition toggling | Benchmark scripts execute the DDL themselves (`DROP INDEX`/`CREATE INDEX`, `ALTER TABLE ... REMOVE PARTITIONING`/re-`PARTITION BY`) against the same 5M-row table, restoring state after each scenario | One dataset to prepare instead of duplicating multi-million-row tables per configuration; re-partitioning/re-indexing 5M rows takes real time but is a one-time cost per scenario, not per request. |
| Deep-page comparison point | Query the actual row at a deep offset (e.g. 4,000,000) directly via SQL to build a matching keyset cursor, then compare `GET /api/transactions/offset?page=<deep>` against `GET /api/transactions?cursor=<same point>` | This is where offset pagination's cost (scan-and-discard N rows) actually diverges from keyset's (indexed range seek) — comparing page 0 of both would show no difference and miss the point of the story. |
| Result reporting | Scripts write a markdown table to `benchmark/results/<date>-results.md`; no separate JSON | Design doc calls for markdown directly; a portfolio README link doesn't need a second machine-readable format that nothing currently consumes. |
| Test coverage | None — each script's correctness is verified by running it and reading the output | Matches the design doc's testing section: benchmark is documented via script output, not covered by an automated suite, unlike `ingest`/`api`/`web`. |

---

### Task 1: Offset-pagination endpoint (`api`)

**Worktree:** `.worktrees/api-module` (branch `task/api-module`, PR #2 — this commit extends that PR)

**Files:**
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java`
- Create: `api/src/main/java/com/realestate/api/web/TransactionOffsetSearchResponse.java`
- Modify: `api/src/main/java/com/realestate/api/web/TransactionSearchController.java`
- Test: `api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java` (extend)
- Test: `api/src/test/java/com/realestate/api/web/TransactionSearchControllerTest.java` (extend)

**Design:** `searchByOffset` shares the same filter predicate as the existing keyset `search`, so the predicate-building logic is extracted into a private `buildPredicate` helper used by both — this is a mechanical DRY refactor needed to add the new method cleanly, not a broader redesign.

- [ ] **Step 1: Write the failing repository test**

Add to `RealEstateTransactionQueryRepositoryTest.java` (reuses the existing `seed()` data: region `11110` has rows `A`@202305, `B`@202306, `C`@202307, ordered newest-first as C, B, A):

```java
@Test
void appliesOffsetToSkipEarlierPages() {
    var condition = new TransactionSearchCondition("11110", null, null, null, null);

    List<RealEstateTransaction> firstPage = queryRepository.searchByOffset(condition, 0, 2);
    List<RealEstateTransaction> secondPage = queryRepository.searchByOffset(condition, 1, 2);

    assertThat(firstPage).extracting(RealEstateTransaction::getAptName).containsExactly("C", "B");
    assertThat(secondPage).extracting(RealEstateTransaction::getAptName).containsExactly("A");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: FAIL — `searchByOffset` doesn't exist yet (compile error)

- [ ] **Step 3: Extract the shared predicate and add `searchByOffset`**

Add to `RealEstateTransactionQueryRepository.java`:
```java
List<RealEstateTransaction> searchByOffset(TransactionSearchCondition condition, int page, int size);
```

Replace the `search` method body in `RealEstateTransactionQueryRepositoryImpl.java` and add the new method + shared helper:
```java
@Override
public List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size) {
    var q = realEstateTransaction;
    var predicate = buildPredicate(condition);

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

@Override
public List<RealEstateTransaction> searchByOffset(TransactionSearchCondition condition, int page, int size) {
    var q = realEstateTransaction;
    var predicate = buildPredicate(condition);

    return queryFactory.selectFrom(q)
        .where(predicate)
        .orderBy(q.dealYm.desc(), q.id.desc())
        .offset((long) page * size)
        .limit(size)
        .fetch();
}

private BooleanBuilder buildPredicate(TransactionSearchCondition condition) {
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
    return predicate;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: PASS

- [ ] **Step 5: Write the failing controller test**

Add to `TransactionSearchControllerTest.java`:
```java
@Test
void offsetSearchReturnsRequestedPage() throws Exception {
    jdbcTemplate.update("""
        INSERT INTO real_estate_transaction
            (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
        VALUES ('11110', '종로구', '두번째아파트', 59.8, 70000, 2023, 7, 2, 202307)
        """);

    mockMvc.perform(get("/api/transactions/offset").param("regionCode", "11110").param("page", "0").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].aptName").value("두번째아파트"))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1));

    mockMvc.perform(get("/api/transactions/offset").param("regionCode", "11110").param("page", "1").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].aptName").value("테스트아파트"));
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew :api:test --tests TransactionSearchControllerTest`
Expected: FAIL — `/api/transactions/offset` doesn't exist yet (404)

- [ ] **Step 7: Write the response DTO and controller endpoint**

`api/src/main/java/com/realestate/api/web/TransactionOffsetSearchResponse.java`:
```java
package com.realestate.api.web;

import java.util.List;

public record TransactionOffsetSearchResponse(List<TransactionResponse> items, int page, int size) {}
```

Add to `TransactionSearchController.java`:
```java
@GetMapping("/api/transactions/offset")
public TransactionOffsetSearchResponse searchByOffset(
        @RequestParam(required = false) String regionCode,
        @RequestParam(required = false) Integer dealYmFrom,
        @RequestParam(required = false) Integer dealYmTo,
        @RequestParam(required = false) BigDecimal minArea,
        @RequestParam(required = false) BigDecimal maxArea,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

    var condition = new TransactionSearchCondition(regionCode, dealYmFrom, dealYmTo, minArea, maxArea);
    List<RealEstateTransaction> results = repository.searchByOffset(condition, page, size);
    return new TransactionOffsetSearchResponse(results.stream().map(TransactionResponse::from).toList(), page, size);
}
```

- [ ] **Step 8: Run the full module suite**

Run: `./gradlew :api:test`
Expected: all tests PASS

- [ ] **Step 9: Commit and push (updates the existing PR #2)**

From `.worktrees/api-module`:
```bash
git add api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java api/src/main/java/com/realestate/api/web/TransactionOffsetSearchResponse.java api/src/main/java/com/realestate/api/web/TransactionSearchController.java api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java api/src/test/java/com/realestate/api/web/TransactionSearchControllerTest.java
git commit -m "feat: add offset-pagination endpoint for benchmark comparison"
git push origin task/api-module
```

---

### Task 2: Cut the `benchmark` branch/worktree and scaffold the Node project

**Files:**
- Create: `.worktrees/benchmark` (new worktree, branch `task/benchmark`, cut from `task/web`'s tip)
- Create: `benchmark/package.json`, `benchmark/db.js`, `benchmark/api.js`

- [ ] **Step 1: Create the branch and worktree**

From the repo root:
```bash
git -C .worktrees/web fetch origin
git worktree add .worktrees/benchmark -b task/benchmark task/web
```

- [ ] **Step 2: Scaffold the Node project**

`benchmark/package.json`:
```json
{
  "name": "benchmark",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "dependencies": {
    "mysql2": "^3.11.0"
  }
}
```

```bash
cd .worktrees/benchmark/benchmark
npm install
```

- [ ] **Step 3: Write the DB connection helper**

`benchmark/db.js`:
```js
import mysql from 'mysql2/promise';

export function createPool() {
  return mysql.createPool({
    host: 'localhost',
    port: 13306,
    user: 'realestate',
    password: 'realestate',
    database: 'realestate',
    connectionLimit: 5,
  });
}
```

- [ ] **Step 4: Write the api fetch helper**

`benchmark/api.js`:
```js
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080';

export async function apiGet(path) {
  const response = await fetch(`${API_BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status} ${path}`);
  }
  return response.json();
}
```

- [ ] **Step 5: Verify the DB connection works**

With `docker compose up -d` running (see Task 9 for the full manual verification), run:
```bash
node -e "import('./db.js').then(async ({createPool}) => { const p = createPool(); const [rows] = await p.query('SELECT 1 AS ok'); console.log(rows); await p.end(); })"
```
Expected: `[ { ok: 1 } ]`

- [ ] **Step 6: Commit**

```bash
git add benchmark/package.json benchmark/package-lock.json benchmark/db.js benchmark/api.js
git commit -m "chore: scaffold benchmark Node project"
```

---

### Task 3: Synthetic seed data generator

**Files:**
- Create: `benchmark/seed/generate-seed-data.js`

**Design:** Row count defaults to 5,000,000 but is overridable via `node generate-seed-data.js <count>` for faster iteration while developing later tasks.

- [ ] **Step 1: Write the generator**

`benchmark/seed/generate-seed-data.js`:
```js
import { createPool } from '../db.js';

const REGION_CODES = [
  '11110', '11140', '11170', '11200', '11215', '11230', '11260', '11290',
  '11305', '11320', '11350', '11380', '11410', '11440', '11470', '11500',
  '11530', '11545', '11560', '11590', '11620', '11650', '11680', '11710', '11740',
];
const APT_NAMES = ['한강뷰', '중앙타워', '푸른마을', '해솔아파트', '센트럴파크', '더샵', '자이', '푸르지오'];
const BATCH_SIZE = 5000;
const TOTAL_ROWS = Number(process.argv[2] ?? 5_000_000);

function randomRow() {
  const year = 2021 + Math.floor(Math.random() * 5);
  const month = 1 + Math.floor(Math.random() * 12);
  const day = 1 + Math.floor(Math.random() * 28);
  const dealYm = year * 100 + month;
  const regionCode = REGION_CODES[Math.floor(Math.random() * REGION_CODES.length)];
  const aptName = APT_NAMES[Math.floor(Math.random() * APT_NAMES.length)];
  const exclusiveArea = (40 + Math.random() * 80).toFixed(2);
  const dealAmount = 30000 + Math.floor(Math.random() * 200000);
  const floor = 1 + Math.floor(Math.random() * 25);
  const buildYear = 1990 + Math.floor(Math.random() * 34);
  return [regionCode, '법정동', aptName, exclusiveArea, dealAmount, year, month, day, dealYm, floor, buildYear];
}

async function main() {
  const pool = createPool();
  console.log(`Seeding ${TOTAL_ROWS.toLocaleString()} rows in batches of ${BATCH_SIZE}...`);
  const started = Date.now();

  for (let inserted = 0; inserted < TOTAL_ROWS; inserted += BATCH_SIZE) {
    const batchSize = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);
    const rows = Array.from({ length: batchSize }, randomRow);
    await pool.query(
      `INSERT INTO real_estate_transaction
        (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym, floor, build_year)
       VALUES ?`,
      [rows],
    );
    const done = inserted + batchSize;
    if (done % 500_000 < BATCH_SIZE) {
      console.log(`  ${done.toLocaleString()} / ${TOTAL_ROWS.toLocaleString()} rows`);
    }
  }

  console.log(`Done in ${((Date.now() - started) / 1000).toFixed(1)}s`);
  await pool.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 2: Verify with a small run**

With `docker compose up -d` running and the `real_estate_transaction` table already created (via `ingest`'s Flyway migration — see Task 9 if it isn't):
```bash
node seed/generate-seed-data.js 1000
```
Expected: logs `Seeding 1,000 rows...` then `Done in ...s`. Confirm with:
```bash
node -e "import('./db.js').then(async ({createPool}) => { const p = createPool(); const [rows] = await p.query('SELECT COUNT(*) AS c FROM real_estate_transaction'); console.log(rows); await p.end(); })"
```
Expected: count reflects the 1,000 inserted rows (plus any pre-existing rows).

- [ ] **Step 3: Commit**

```bash
git add benchmark/seed/generate-seed-data.js
git commit -m "feat: add synthetic seed data generator"
```

---

### Task 4: Measurement, DDL toggle, and cursor utilities

**Files:**
- Create: `benchmark/measure.js`
- Create: `benchmark/toggle.js`
- Create: `benchmark/cursor.js`

**Design:** `measure.js` times a sequence of requests (a few discarded warmup calls, then N timed calls) and summarizes into p50/p95/avg. `toggle.js` wraps the DDL for turning the index and partitioning on/off. `cursor.js` reproduces `api`'s `TransactionCursor.encode()` format (`"<dealYm>:<id>"`, base64url, no padding) so the pagination scenario can construct a cursor pointing at an arbitrary row without going through the API first.

- [ ] **Step 1: Write `measure.js`**

`benchmark/measure.js`:
```js
export async function measureSequential(requestFn, { warmup = 3, iterations = 20 } = {}) {
  for (let i = 0; i < warmup; i++) {
    await requestFn();
  }

  const durationsMs = [];
  for (let i = 0; i < iterations; i++) {
    const start = performance.now();
    await requestFn();
    durationsMs.push(performance.now() - start);
  }

  return summarize(durationsMs);
}

export function summarize(durationsMs) {
  const sorted = [...durationsMs].sort((a, b) => a - b);
  const avg = sorted.reduce((sum, v) => sum + v, 0) / sorted.length;
  return {
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    avg,
    samples: sorted.length,
  };
}

function percentile(sortedAscending, p) {
  const index = Math.floor(p * (sortedAscending.length - 1));
  return sortedAscending[index];
}
```

- [ ] **Step 2: Verify `summarize` with a quick manual check**

```bash
node -e "import('./measure.js').then(m => console.log(m.summarize([10, 20, 30, 40, 100])))"
```
Expected: `{ p50: 30, p95: 100, avg: 40, samples: 5 }` (p50 = sorted[floor(0.5*4)] = sorted[2] = 30; p95 = sorted[floor(0.95*4)] = sorted[3] = 40 — **check the actual printed p95 value against this hand calculation before moving on**, since off-by-one in the percentile index is an easy mistake to introduce silently)

- [ ] **Step 3: Write `toggle.js`**

`benchmark/toggle.js`:
```js
export async function dropIndex(pool) {
  await pool.query('DROP INDEX idx_region_ym ON real_estate_transaction');
}

export async function createIndex(pool) {
  await pool.query('CREATE INDEX idx_region_ym ON real_estate_transaction (region_code, deal_ym)');
}

export async function removePartitioning(pool) {
  await pool.query('ALTER TABLE real_estate_transaction REMOVE PARTITIONING');
}

export async function applyPartitioning(pool) {
  await pool.query(`
    ALTER TABLE real_estate_transaction
    PARTITION BY RANGE (deal_ym) (
      PARTITION p2020 VALUES LESS THAN (202101),
      PARTITION p2021 VALUES LESS THAN (202201),
      PARTITION p2022 VALUES LESS THAN (202301),
      PARTITION p2023 VALUES LESS THAN (202401),
      PARTITION p2024 VALUES LESS THAN (202501),
      PARTITION p2025 VALUES LESS THAN (202601),
      PARTITION pmax VALUES LESS THAN MAXVALUE
    )
  `);
}
```

**Note for implementer:** this partition list must stay identical to `ingest`'s `V1__create_real_estate_transaction.sql` — if that migration's partition ranges ever change, update this file to match.

- [ ] **Step 4: Write `cursor.js`**

`benchmark/cursor.js`:
```js
export function encodeCursor(dealYm, id) {
  return Buffer.from(`${dealYm}:${id}`).toString('base64url');
}
```

- [ ] **Step 5: Verify `encodeCursor` matches the Java side**

```bash
node -e "import('./cursor.js').then(c => console.log(c.encodeCursor(202307, 42)))"
```
Expected output is a base64url string with no `=` padding and no `+`/`/` characters. Cross-check it decodes back to `202307:42`:
```bash
node -e "import('./cursor.js').then(c => { const encoded = c.encodeCursor(202307, 42); console.log(Buffer.from(encoded, 'base64url').toString()); })"
```
Expected: `202307:42`

- [ ] **Step 6: Commit**

```bash
git add benchmark/measure.js benchmark/toggle.js benchmark/cursor.js
git commit -m "feat: add measurement, DDL toggle, and cursor utilities"
```

---

### Task 5: Index on/off scenario

**Files:**
- Create: `benchmark/scenarios/index-on-off.js`

- [ ] **Step 1: Write the scenario**

`benchmark/scenarios/index-on-off.js`:
```js
import { measureSequential } from '../measure.js';
import { dropIndex, createIndex } from '../toggle.js';
import { apiGet } from '../api.js';

export async function runIndexScenario(pool) {
  const request = () => apiGet('/api/transactions?regionCode=11110&size=20');

  const withIndex = await measureSequential(request);

  await dropIndex(pool);
  const withoutIndex = await measureSequential(request);
  await createIndex(pool);

  return [
    { scenario: '인덱스 ON', ...withIndex },
    { scenario: '인덱스 OFF', ...withoutIndex },
  ];
}
```

- [ ] **Step 2: Verify against the small seeded dataset**

With `api` running (`./gradlew :api:bootRun` from `.worktrees/benchmark`) and the 1,000-row seed from Task 3 in place:
```bash
node -e "import('./db.js').then(async ({createPool}) => { const {runIndexScenario} = await import('./scenarios/index-on-off.js'); const p = createPool(); console.log(await runIndexScenario(p)); await p.end(); })"
```
Expected: an array of two `{ scenario, p50, p95, avg, samples }` objects. At 1,000 rows the ON/OFF difference will likely be small or even noisy (the story only becomes visible at millions of rows — that's expected here; Task 9's full run is where the real gap shows up). Confirm the index was restored afterward:
```bash
node -e "import('./db.js').then(async ({createPool}) => { const p = createPool(); const [rows] = await p.query(\"SHOW INDEX FROM real_estate_transaction WHERE Key_name = 'idx_region_ym'\"); console.log(rows.length); await p.end(); })"
```
Expected: `2` (`idx_region_ym` is a composite index on `region_code, deal_ym` — `SHOW INDEX` returns one row per column, not one row per index)

- [ ] **Step 3: Commit**

```bash
git add benchmark/scenarios/index-on-off.js
git commit -m "feat: add index on/off benchmark scenario"
```

---

### Task 6: Partition on/off scenario

**Files:**
- Create: `benchmark/scenarios/partition-on-off.js`

- [ ] **Step 1: Write the scenario**

`benchmark/scenarios/partition-on-off.js`:
```js
import { measureSequential } from '../measure.js';
import { removePartitioning, applyPartitioning } from '../toggle.js';
import { apiGet } from '../api.js';

export async function runPartitionScenario(pool) {
  const request = () => apiGet('/api/transactions?regionCode=11110&dealYmFrom=202601&dealYmTo=202607&size=20');

  const withPartition = await measureSequential(request);

  await removePartitioning(pool);
  const withoutPartition = await measureSequential(request);
  await applyPartitioning(pool);

  return [
    { scenario: '파티션 ON', ...withPartition },
    { scenario: '파티션 OFF', ...withoutPartition },
  ];
}
```

**Design note:** the request filters to a narrow `dealYm` range so partition pruning has something to prune — a request with no `dealYm` filter would scan all partitions either way and wouldn't show a difference.

- [ ] **Step 2: Verify against the small seeded dataset**

Same pattern as Task 5 Step 2, substituting `runPartitionScenario`. Confirm partitioning was restored afterward:
```bash
node -e "import('./db.js').then(async ({createPool}) => { const p = createPool(); const [rows] = await p.query(\"SELECT COUNT(*) AS c FROM information_schema.PARTITIONS WHERE TABLE_NAME = 'real_estate_transaction' AND PARTITION_NAME IS NOT NULL\"); console.log(rows); await p.end(); })"
```
Expected: `c` equals `7` (the 7 partitions defined in `toggle.js`)

- [ ] **Step 3: Commit**

```bash
git add benchmark/scenarios/partition-on-off.js
git commit -m "feat: add partition on/off benchmark scenario"
```

---

### Task 7: Offset vs keyset (deep page) scenario

**Files:**
- Create: `benchmark/scenarios/offset-vs-keyset.js`

- [ ] **Step 1: Write the scenario**

`benchmark/scenarios/offset-vs-keyset.js`:
```js
import { measureSequential } from '../measure.js';
import { encodeCursor } from '../cursor.js';
import { apiGet } from '../api.js';

const PAGE_SIZE = 20;

export async function runPaginationScenario(pool, deepOffset = 4_000_000) {
  const [rows] = await pool.query(
    'SELECT deal_ym, id FROM real_estate_transaction ORDER BY deal_ym DESC, id DESC LIMIT 1 OFFSET ?',
    [deepOffset],
  );
  const deepRow = rows[0];
  const cursor = encodeCursor(deepRow.deal_ym, deepRow.id);
  const deepPage = Math.floor(deepOffset / PAGE_SIZE);

  const offsetResult = await measureSequential(
    () => apiGet(`/api/transactions/offset?page=${deepPage}&size=${PAGE_SIZE}`),
  );
  const keysetResult = await measureSequential(
    () => apiGet(`/api/transactions?cursor=${cursor}&size=${PAGE_SIZE}`),
  );

  return [
    { scenario: `Offset (page ${deepPage})`, ...offsetResult },
    { scenario: 'Keyset (동일 지점)', ...keysetResult },
  ];
}
```

**Design note:** `deepOffset` defaults to 4,000,000 (near the end of the planned 5,000,000-row dataset) but is a parameter so Task 9's smaller verification run can pass a value that actually exists in a 1,000-row table.

- [ ] **Step 2: Verify against the small seeded dataset**

```bash
node -e "import('./db.js').then(async ({createPool}) => { const {runPaginationScenario} = await import('./scenarios/offset-vs-keyset.js'); const p = createPool(); console.log(await runPaginationScenario(p, 500)); await p.end(); })"
```
Expected: an array of two `{ scenario, p50, p95, avg, samples }` objects (using offset 500, since the small dataset only has ~1,000 rows). At this scale, offset vs. keyset will likely look similar — the divergence is expected only at millions of rows and a deep offset, which Task 9's full run covers.

- [ ] **Step 3: Commit**

```bash
git add benchmark/scenarios/offset-vs-keyset.js
git commit -m "feat: add offset vs keyset deep-page benchmark scenario"
```

---

### Task 8: Markdown report writer and orchestrator

**Files:**
- Create: `benchmark/report.js`
- Create: `benchmark/run-benchmark.js`

- [ ] **Step 1: Write the report writer**

`benchmark/report.js`:
```js
import { writeFileSync, mkdirSync } from 'node:fs';

export function buildMarkdownReport(sections) {
  const lines = ['# Benchmark Results', '', `Generated: ${new Date().toISOString()}`, ''];
  for (const section of sections) {
    lines.push(`## ${section.title}`, '', '| 시나리오 | p50(ms) | p95(ms) | 평균(ms) | 샘플수 |', '|---|---|---|---|---|');
    for (const row of section.rows) {
      lines.push(`| ${row.scenario} | ${row.p50.toFixed(1)} | ${row.p95.toFixed(1)} | ${row.avg.toFixed(1)} | ${row.samples} |`);
    }
    lines.push('');
  }
  return lines.join('\n');
}

export function writeReport(markdown) {
  mkdirSync('benchmark/results', { recursive: true });
  const path = `benchmark/results/${new Date().toISOString().slice(0, 10)}-results.md`;
  writeFileSync(path, markdown);
  return path;
}
```

- [ ] **Step 2: Write the orchestrator**

`benchmark/run-benchmark.js`:
```js
import { createPool } from './db.js';
import { runIndexScenario } from './scenarios/index-on-off.js';
import { runPartitionScenario } from './scenarios/partition-on-off.js';
import { runPaginationScenario } from './scenarios/offset-vs-keyset.js';
import { buildMarkdownReport, writeReport } from './report.js';

async function main() {
  const pool = createPool();

  console.log('Running index on/off scenario...');
  const indexRows = await runIndexScenario(pool);

  console.log('Running partition on/off scenario...');
  const partitionRows = await runPartitionScenario(pool);

  console.log('Running offset vs keyset scenario...');
  const paginationRows = await runPaginationScenario(pool);

  const markdown = buildMarkdownReport([
    { title: '인덱스 유무', rows: indexRows },
    { title: '파티션 유무', rows: partitionRows },
    { title: 'Offset vs Keyset (딥페이지)', rows: paginationRows },
  ]);
  const path = writeReport(markdown);
  console.log(`Report written to ${path}`);

  await pool.end();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 3: Verify against the small seeded dataset**

Run: `node run-benchmark.js`
Expected: three "Running ... scenario..." log lines, then "Report written to benchmark/results/<today>-results.md". Open that file and confirm it has three `##` sections each with a populated table.

- [ ] **Step 4: Commit**

Leave `benchmark/results/` out of this commit — it was created by Step 3's small-scale sanity run, not the real benchmark. Task 9 generates and commits the real results.

```bash
git add benchmark/report.js benchmark/run-benchmark.js
git commit -m "feat: add markdown report writer and benchmark orchestrator"
```

---

### Task 9: README, full-scale run, and PR

**Files:**
- Create: `benchmark/README.md`
- Create: `benchmark/results/<date>-results.md` (generated by the full run, not hand-written)

- [ ] **Step 1: Write the README**

`benchmark/README.md`:
```markdown
# Benchmark scripts

Measures the impact of indexing, partitioning, and keyset vs. offset pagination on `real_estate_transaction` query latency, against a synthetic 5,000,000-row dataset (this environment has no MOLIT API key, so real ingestion can't reach benchmark scale — see the design doc's "벤치마크 데이터 소스" decision).

## Prerequisites

- `docker compose up -d` (MariaDB + Redis) from the repo root
- `real_estate_transaction` table created (via `ingest`'s Flyway migration — run `ingest`'s app once, or apply `ingest/src/main/resources/db/migration/V1__create_real_estate_transaction.sql` directly)
- `api` running: `./gradlew :api:bootRun` (note: `application.yml`'s Redis port is hardcoded to 6379, but `docker-compose.yml` maps Redis to 16379 — pass `--spring.data.redis.port=16379` until that's fixed)

## Running

```bash
cd benchmark
npm install
node seed/generate-seed-data.js        # ~5,000,000 rows, defaults; pass a number to override
node run-benchmark.js
```

Results are written to `benchmark/results/<date>-results.md`.
```

- [ ] **Step 2: Run the full-scale benchmark**

With `docker compose up -d`, the schema created, and `api` running:
```bash
cd benchmark
node seed/generate-seed-data.js
node run-benchmark.js
```
Expected: seeding logs progress every 500,000 rows and finishes; the benchmark run produces `benchmark/results/<date>-results.md` showing index OFF and partition OFF noticeably slower (higher p50/p95) than ON, and the offset deep-page scenario noticeably slower than the equivalent keyset request.

- [ ] **Step 3: Commit the README and results**

```bash
git add benchmark/README.md benchmark/results/
git commit -m "docs: add benchmark README and full-scale results"
```

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin task/benchmark
gh pr create --base task/web --head task/benchmark --title "Task/benchmark scripts" --body "$(cat <<'EOF'
## Summary
- Node.js scripts: synthetic 5M-row seed generator, and three benchmark scenarios (index on/off, partition on/off, offset vs keyset deep-page) that toggle schema state via DDL and measure p50/p95/avg against the running api
- Adds GET /api/transactions/offset to the api module (this PR's base branch, via task/api-module) for the pagination comparison

## Test plan
- [x] api: ./gradlew :api:test
- [x] benchmark: each scenario verified against a small seeded dataset per its task in docs/superpowers/plans/2026-07-22-benchmark-scripts.md
- [x] Full-scale run: 5,000,000-row seed + node run-benchmark.js, results committed under benchmark/results/
EOF
)"
```

Expected: PR opened targeting `task/web` — this is the last of the four stacked branches (`task/infra-and-ingest` → `task/api-module` → `task/web` → `task/benchmark`), none merged to `main` yet.
