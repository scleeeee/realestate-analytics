# Web Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Note on this project:** For realestate-analytics specifically, the human partner wants to write/pair on the code directly rather than have it built autonomously by subagents — see `docs/superpowers/plans/2026-07-21-api-module.md` and the design discussion that produced this plan. Treat this plan as a shared design reference to implement together in-session, not as a queue to dispatch to background implementer subagents.

**Goal:** Stand up the `web` module — a React SPA with a search page (region + period + area filters, keyset-paginated transaction list) and a region detail page (monthly price trend chart) that consume the `api` module's endpoints. Also extends `api` with a new range-statistics endpoint the chart needs.

**Architecture:** `web` is a standalone Vite + React project (not a Gradle submodule) living in a new `task/web` branch/worktree cut from `task/api-module`'s tip — it depends on the range endpoint added in Task 1. State is local to components/hooks (no Redux/Pinia); two custom hooks (`useTransactionSearch`, `useRegionStats`) wrap `fetch` calls to `api`. Region name→code lookup is a static bundled JSON file rather than a new API — see design decisions below. `react-router-dom` provides the two routes.

**Tech Stack:** React 18 (JavaScript, no TypeScript), Vite, react-router-dom, Chart.js + react-chartjs-2, Vitest + Testing Library (`@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`). Backend addition: Spring Boot + QueryDSL (matches existing `api` module stack).

**Note on scope:** This is Plan 3 of 4 for the realestate-analytics project (see `docs/superpowers/specs/2026-07-20-realestate-analytics-design.md`). The benchmark scripts are a separate follow-up plan.

---

### Design decisions locked in during planning discussion

| Decision | Choice | Reason |
|---|---|---|
| Frontend framework | React (Vite, JavaScript) instead of the original Vue3 choice | Frontend framework doesn't affect the portfolio's core selling point (backend performance tuning); user has zero React experience and wants to learn it hands-on through this project |
| Region search UX | Static bundled `region-codes.json`, dropdown by name | `api` only accepts `regionCode`; no region-name lookup API exists. Bundling avoids adding a new endpoint for a lookup that never changes at runtime |
| Region data scope | Seoul's 25 자치구 only (not the full nationwide 법정동코드 table) | The actual demo data (`ingest`'s `application.yml`) only seeds `11110`(종로구)/`11140`(중구). Bundling ~3000+ nationwide 법정동 rows for a 2-region demo is over-scoped; Seoul's 25-gu table is a well-known, verifiable standard subset that comfortably covers demo + manual testing |
| Price trend data | New `GET /api/regions/{regionCode}/stats/range?from=&to=` endpoint on `api`, returns monthly stats list | `api` only had single-month `/stats`. Doing the range server-side (one QueryDSL `GROUP BY dealYm` query) avoids the web client firing up to 12 sequential requests per chart render |
| Branch for the range endpoint | Committed onto the existing `task/api-module` branch (PR #2, still open) | Keeps all `api`-module changes in one PR; no separate merge-ordering complexity |
| Screens | 2 pages: search/list (`/`) and region detail/chart (`/regions/:regionCode`) via `react-router-dom` | Matches the design doc's "지역 검색 + 시세추이 차트" scope without over-building navigation |
| Testing | Vitest + Testing Library on hooks and components only, no e2e/browser tests | Matches the design doc's "minimal frontend" framing; heavy integration testing is where `ingest`/`api` already carry the weight |
| Error handling | Component-level error message on failed fetch; no global error boundary | Screens are simple enough that a boundary adds ceremony without benefit |

---

### Task 1: Region×month range statistics endpoint (`api`)

**Worktree:** `.worktrees/api-module` (branch `task/api-module`, PR #2 — this commit extends that PR, not a new branch)

**Files:**
- Create: `api/src/main/java/com/realestate/api/domain/RegionMonthStats.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java`
- Modify: `api/src/main/java/com/realestate/api/domain/RegionStatsService.java`
- Modify: `api/src/main/java/com/realestate/api/web/RegionStatsController.java`
- Test: `api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java` (extend)
- Test: `api/src/test/java/com/realestate/api/domain/RegionStatsServiceTest.java` (extend)

- [ ] **Step 1: Write the failing repository tests**

Add to `RealEstateTransactionQueryRepositoryTest.java` (reuses the existing `seed()` data: region `11110` has rows at `202305`/`202306`/`202307`, region `11140` has one row at `202307`):

```java
@Test
void aggregatesMonthlyStatsAcrossRange() {
    List<RegionMonthStats> monthly = queryRepository.statsForRange("11110", 202305, 202307);

    assertThat(monthly).extracting(RegionMonthStats::dealYm)
        .containsExactly(202305, 202306, 202307);
    assertThat(monthly).extracting(RegionMonthStats::count)
        .containsExactly(1L, 1L, 1L);
}

@Test
void excludesMonthsOutsideTheRequestedRange() {
    List<RegionMonthStats> monthly = queryRepository.statsForRange("11110", 202306, 202307);

    assertThat(monthly).extracting(RegionMonthStats::dealYm)
        .containsExactly(202306, 202307);
}
```

Add `import java.util.List;` if not already present in the test file (it already is, from the existing `search` tests).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: FAIL — `RegionMonthStats`/`statsForRange` don't exist yet (compile error)

- [ ] **Step 3: Write `RegionMonthStats` and the range query**

`api/src/main/java/com/realestate/api/domain/RegionMonthStats.java`:
```java
package com.realestate.api.domain;

import java.math.BigDecimal;

public record RegionMonthStats(int dealYm, long count, BigDecimal avgDealAmount) {}
```

Add to `RealEstateTransactionQueryRepository.java`:
```java
List<RegionMonthStats> statsForRange(String regionCode, int dealYmFrom, int dealYmTo);
```

Add to `RealEstateTransactionQueryRepositoryImpl.java` (add `import com.querydsl.core.Tuple;` at the top):
```java
@Override
public List<RegionMonthStats> statsForRange(String regionCode, int dealYmFrom, int dealYmTo) {
    var q = realEstateTransaction;
    List<Tuple> rows = queryFactory
        .select(q.dealYm, q.count(), q.dealAmount.avg())
        .from(q)
        .where(q.regionCode.eq(regionCode), q.dealYm.between(dealYmFrom, dealYmTo))
        .groupBy(q.dealYm)
        .orderBy(q.dealYm.asc())
        .fetch();

    return rows.stream()
        .map(row -> {
            Integer dealYm = row.get(q.dealYm);
            Long count = row.get(q.count());
            Double avg = row.get(q.dealAmount.avg());
            BigDecimal safeAvg = (avg != null) ? BigDecimal.valueOf(avg) : BigDecimal.ZERO;
            return new RegionMonthStats(dealYm, count != null ? count : 0L, safeAvg);
        })
        .toList();
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :api:test --tests RealEstateTransactionQueryRepositoryTest`
Expected: PASS

- [ ] **Step 5: Write a failing service-layer cache test**

Add to `RegionStatsServiceTest.java`:
```java
@Test
void cachesRangeStatsAcrossRepeatedCalls() {
    List<RegionMonthStats> first = regionStatsService.getStatsRange("11110", 202307, 202307);
    jdbcTemplate.update("""
        INSERT INTO real_estate_transaction
            (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
        VALUES ('11110', '종로구', 'C', 59.8, 60000, 2023, 7, 3, 202307)
        """);
    List<RegionMonthStats> second = regionStatsService.getStatsRange("11110", 202307, 202307);

    assertThat(first.get(0).count()).isEqualTo(1);
    assertThat(second.get(0).count()).isEqualTo(1); // still 1 — cached, not re-queried
}
```

Add `import java.util.List;` to the test file if not already present.

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew :api:test --tests RegionStatsServiceTest`
Expected: FAIL — `getStatsRange` doesn't exist yet (compile error)

- [ ] **Step 7: Write the service method and controller endpoint**

Add to `RegionStatsService.java` (add `import java.util.List;`):
```java
@Cacheable(value = "regionStatsRange", key = "#regionCode + ':' + #dealYmFrom + ':' + #dealYmTo")
public List<RegionMonthStats> getStatsRange(String regionCode, int dealYmFrom, int dealYmTo) {
    return repository.statsForRange(regionCode, dealYmFrom, dealYmTo);
}
```

Add to `RegionStatsController.java` (add `import java.util.List;`):
```java
@GetMapping("/api/regions/{regionCode}/stats/range")
public List<RegionStatsResponse> statsRange(
        @PathVariable String regionCode,
        @RequestParam int from,
        @RequestParam int to) {
    return regionStatsService.getStatsRange(regionCode, from, to).stream()
        .map(s -> new RegionStatsResponse(regionCode, s.dealYm(), s.count(), s.avgDealAmount()))
        .toList();
}
```

- [ ] **Step 8: Run the full module suite**

Run: `./gradlew :api:test`
Expected: all tests PASS

- [ ] **Step 9: Commit and push (updates the existing PR #2)**

From `.worktrees/api-module`:
```bash
git add api/src/main/java/com/realestate/api/domain/RegionMonthStats.java api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepository.java api/src/main/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryImpl.java api/src/main/java/com/realestate/api/domain/RegionStatsService.java api/src/main/java/com/realestate/api/web/RegionStatsController.java api/src/test/java/com/realestate/api/domain/RealEstateTransactionQueryRepositoryTest.java api/src/test/java/com/realestate/api/domain/RegionStatsServiceTest.java
git commit -m "feat: add region monthly stats range endpoint"
git push origin task/api-module
```

---

### Task 2: Cut the `web` branch/worktree and scaffold the Vite React app

**Files:**
- Create: `.worktrees/web` (new worktree, branch `task/web`, cut from `task/api-module`'s new tip)
- Create: `web/package.json`, `web/index.html`, `web/vite.config.js`, `web/src/main.jsx`, `web/src/App.jsx`, `web/src/setupTests.js` (mix of Vite-generated and hand-edited)

- [ ] **Step 1: Create the branch and worktree**

From the repo root (`C:\Users\sclee\Desktop\realestate-analytics`):
```bash
git -C .worktrees/api-module fetch origin
git worktree add .worktrees/web -b task/web task/api-module
```

- [ ] **Step 2: Scaffold the Vite React app**

```bash
cd .worktrees/web
npm create vite@latest web -- --template react
cd web
npm install
```

Expected: a `web/` directory containing `package.json`, `index.html`, `src/App.jsx`, `src/main.jsx`, etc.

- [ ] **Step 3: Install runtime dependencies**

```bash
npm install react-router-dom chart.js react-chartjs-2
```

- [ ] **Step 4: Install test dependencies**

```bash
npm install -D vitest jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event
```

- [ ] **Step 5: Configure Vite for the dev proxy and Vitest**

Replace `web/vite.config.js`:
```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/setupTests.js',
    globals: true,
  },
});
```

Create `web/src/setupTests.js`:
```js
import '@testing-library/jest-dom';
```

- [ ] **Step 6: Add the test script**

In `web/package.json`, add to `"scripts"`:
```json
"test": "vitest run"
```

- [ ] **Step 7: Remove the Vite template's default demo content**

Delete `web/src/App.css`, `web/src/assets/react.svg`, `web/public/vite.svg` if present. Replace `web/src/App.jsx` with a placeholder (later tasks fill this in):
```jsx
export function App() {
  return <div>web module scaffold</div>;
}
```

Update `web/src/main.jsx` to match the named export:
```jsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [ ] **Step 8: Verify the build and test runner both work**

```bash
npm run build
npm test
```

Expected: `npm run build` succeeds; `npm test` reports "no test files found" (expected — none written yet) without erroring.

- [ ] **Step 9: Commit**

```bash
git add web/
git commit -m "chore: scaffold web module (Vite + React)"
```

---

### Task 3: API client + static region-code data

**Files:**
- Create: `web/src/api/client.js`
- Create: `web/src/data/region-codes.json`

- [ ] **Step 1: Write the fetch wrapper**

`web/src/api/client.js`:
```js
export async function apiGet(path) {
  const response = await fetch(path);
  if (!response.ok) {
    throw new Error(`API request failed: ${response.status} ${path}`);
  }
  return response.json();
}
```

- [ ] **Step 2: Write the region code data**

`web/src/data/region-codes.json` (Seoul's 25 자치구 — see design decision above on scope):
```json
[
  { "code": "11110", "name": "서울특별시 종로구" },
  { "code": "11140", "name": "서울특별시 중구" },
  { "code": "11170", "name": "서울특별시 용산구" },
  { "code": "11200", "name": "서울특별시 성동구" },
  { "code": "11215", "name": "서울특별시 광진구" },
  { "code": "11230", "name": "서울특별시 동대문구" },
  { "code": "11260", "name": "서울특별시 중랑구" },
  { "code": "11290", "name": "서울특별시 성북구" },
  { "code": "11305", "name": "서울특별시 강북구" },
  { "code": "11320", "name": "서울특별시 도봉구" },
  { "code": "11350", "name": "서울특별시 노원구" },
  { "code": "11380", "name": "서울특별시 은평구" },
  { "code": "11410", "name": "서울특별시 서대문구" },
  { "code": "11440", "name": "서울특별시 마포구" },
  { "code": "11470", "name": "서울특별시 양천구" },
  { "code": "11500", "name": "서울특별시 강서구" },
  { "code": "11530", "name": "서울특별시 구로구" },
  { "code": "11545", "name": "서울특별시 금천구" },
  { "code": "11560", "name": "서울특별시 영등포구" },
  { "code": "11590", "name": "서울특별시 동작구" },
  { "code": "11620", "name": "서울특별시 관악구" },
  { "code": "11650", "name": "서울특별시 서초구" },
  { "code": "11680", "name": "서울특별시 강남구" },
  { "code": "11710", "name": "서울특별시 송파구" },
  { "code": "11740", "name": "서울특별시 강동구" }
]
```

**Note for implementer:** before relying on this list beyond the demo, cross-check it against the 행정표준코드관리시스템 법정동코드 전체자료 (or `ingest`'s actual seeded `region-codes` config) — this plan hand-transcribed the well-known Seoul 25-gu code table, but verify it against an authoritative source if the web module ever needs to search outside Seoul.

- [ ] **Step 3: Commit**

```bash
git add web/src/api/client.js web/src/data/region-codes.json
git commit -m "feat: add api client and static region-code data"
```

---

### Task 4: `useTransactionSearch` hook

**Files:**
- Create: `web/src/hooks/useTransactionSearch.js`
- Test: `web/src/hooks/useTransactionSearch.test.js`

**Design:** wraps `GET /api/transactions`. `search(condition)` replaces `items`; `loadMore(condition)` appends using `nextCursor`. Both track `loading`/`error`.

- [ ] **Step 1: Write the failing test**

`web/src/hooks/useTransactionSearch.test.js`:
```js
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useTransactionSearch } from './useTransactionSearch';

describe('useTransactionSearch', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  it('loads search results into items', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ items: [{ id: 1, aptName: 'A' }], nextCursor: 'abc' }),
    });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });

    expect(result.current.items).toEqual([{ id: 1, aptName: 'A' }]);
    expect(result.current.nextCursor).toBe('abc');
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining('regionCode=11110'));
  });

  it('appends results on loadMore using the cursor', async () => {
    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ items: [{ id: 1 }], nextCursor: 'cursor1' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ items: [{ id: 2 }], nextCursor: null }) });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });
    await act(async () => {
      await result.current.loadMore({ regionCode: '11110' });
    });

    expect(result.current.items).toEqual([{ id: 1 }, { id: 2 }]);
    expect(result.current.nextCursor).toBeNull();
    expect(global.fetch).toHaveBeenLastCalledWith(expect.stringContaining('cursor=cursor1'));
  });

  it('sets an error message when the request fails', async () => {
    global.fetch.mockResolvedValueOnce({ ok: false, status: 500 });
    const { result } = renderHook(() => useTransactionSearch());

    await act(async () => {
      await result.current.search({ regionCode: '11110' });
    });

    expect(result.current.error).toContain('500');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- useTransactionSearch`
Expected: FAIL — `useTransactionSearch.js` doesn't exist yet

- [ ] **Step 3: Write the hook**

`web/src/hooks/useTransactionSearch.js`:
```js
import { useCallback, useState } from 'react';
import { apiGet } from '../api/client';

export function useTransactionSearch() {
  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const runSearch = useCallback(async (condition, cursor, append) => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet(`/api/transactions?${buildParams(condition, cursor)}`);
      setItems((prev) => (append ? [...prev, ...data.items] : data.items));
      setNextCursor(data.nextCursor);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const search = useCallback((condition) => runSearch(condition, null, false), [runSearch]);
  const loadMore = useCallback((condition) => runSearch(condition, nextCursor, true), [runSearch, nextCursor]);

  return { items, nextCursor, loading, error, search, loadMore };
}

function buildParams(condition, cursor) {
  const params = new URLSearchParams();
  if (condition.regionCode) params.set('regionCode', condition.regionCode);
  if (condition.dealYmFrom) params.set('dealYmFrom', condition.dealYmFrom);
  if (condition.dealYmTo) params.set('dealYmTo', condition.dealYmTo);
  if (condition.minArea) params.set('minArea', condition.minArea);
  if (condition.maxArea) params.set('maxArea', condition.maxArea);
  if (cursor) params.set('cursor', cursor);
  return params.toString();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- useTransactionSearch`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web/src/hooks/useTransactionSearch.js web/src/hooks/useTransactionSearch.test.js
git commit -m "feat: add useTransactionSearch hook"
```

---

### Task 5: `useRegionStats` hook

**Files:**
- Create: `web/src/hooks/useRegionStats.js`
- Test: `web/src/hooks/useRegionStats.test.js`

**Design:** wraps `GET /api/regions/{regionCode}/stats/range?from=&to=`.

- [ ] **Step 1: Write the failing test**

`web/src/hooks/useRegionStats.test.js`:
```js
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useRegionStats } from './useRegionStats';

describe('useRegionStats', () => {
  beforeEach(() => {
    global.fetch = vi.fn();
  });

  it('loads monthly stats for the given range', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ([{ regionCode: '11110', dealYm: 202301, count: 2, avgDealAmount: 90000 }]),
    });
    const { result } = renderHook(() => useRegionStats());

    await act(async () => {
      await result.current.loadRange('11110', 202301, 202312);
    });

    expect(result.current.monthlyStats).toEqual([
      { regionCode: '11110', dealYm: 202301, count: 2, avgDealAmount: 90000 },
    ]);
    expect(global.fetch).toHaveBeenCalledWith('/api/regions/11110/stats/range?from=202301&to=202312');
  });

  it('sets an error message when the request fails', async () => {
    global.fetch.mockResolvedValueOnce({ ok: false, status: 404 });
    const { result } = renderHook(() => useRegionStats());

    await act(async () => {
      await result.current.loadRange('11110', 202301, 202312);
    });

    expect(result.current.error).toContain('404');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- useRegionStats`
Expected: FAIL — `useRegionStats.js` doesn't exist yet

- [ ] **Step 3: Write the hook**

`web/src/hooks/useRegionStats.js`:
```js
import { useCallback, useState } from 'react';
import { apiGet } from '../api/client';

export function useRegionStats() {
  const [monthlyStats, setMonthlyStats] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const loadRange = useCallback(async (regionCode, from, to) => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet(`/api/regions/${regionCode}/stats/range?from=${from}&to=${to}`);
      setMonthlyStats(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  return { monthlyStats, loading, error, loadRange };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- useRegionStats`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web/src/hooks/useRegionStats.js web/src/hooks/useRegionStats.test.js
git commit -m "feat: add useRegionStats hook"
```

---

### Task 6: `RegionSearch` component

**Files:**
- Create: `web/src/components/RegionSearch.jsx`
- Test: `web/src/components/RegionSearch.test.jsx`

- [ ] **Step 1: Write the failing test**

`web/src/components/RegionSearch.test.jsx`:
```jsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { RegionSearch } from './RegionSearch';

describe('RegionSearch', () => {
  it('calls onSearch with the selected condition', async () => {
    const onSearch = vi.fn();
    const user = userEvent.setup();
    render(<RegionSearch onSearch={onSearch} />);

    await user.selectOptions(screen.getByLabelText('지역'), '11110');
    await user.type(screen.getByPlaceholderText('from'), '202301');
    await user.type(screen.getByPlaceholderText('to'), '202312');
    await user.click(screen.getByRole('button', { name: '검색' }));

    expect(onSearch).toHaveBeenCalledWith({
      regionCode: '11110',
      dealYmFrom: '202301',
      dealYmTo: '202312',
      minArea: '',
      maxArea: '',
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- RegionSearch`
Expected: FAIL — `RegionSearch.jsx` doesn't exist yet

- [ ] **Step 3: Write the component**

`web/src/components/RegionSearch.jsx`:
```jsx
import { useState } from 'react';
import regionCodes from '../data/region-codes.json';

export function RegionSearch({ onSearch }) {
  const [regionCode, setRegionCode] = useState('');
  const [dealYmFrom, setDealYmFrom] = useState('');
  const [dealYmTo, setDealYmTo] = useState('');
  const [minArea, setMinArea] = useState('');
  const [maxArea, setMaxArea] = useState('');

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ regionCode, dealYmFrom, dealYmTo, minArea, maxArea });
  }

  return (
    <form onSubmit={handleSubmit}>
      <label htmlFor="regionCode">지역</label>
      <select id="regionCode" value={regionCode} onChange={(e) => setRegionCode(e.target.value)} required>
        <option value="">지역 선택</option>
        {regionCodes.map((region) => (
          <option key={region.code} value={region.code}>{region.name}</option>
        ))}
      </select>

      <input value={dealYmFrom} onChange={(e) => setDealYmFrom(e.target.value)} placeholder="from" aria-label="기간 시작(YYYYMM)" />
      <input value={dealYmTo} onChange={(e) => setDealYmTo(e.target.value)} placeholder="to" aria-label="기간 종료(YYYYMM)" />
      <input value={minArea} onChange={(e) => setMinArea(e.target.value)} placeholder="최소 평형(㎡)" aria-label="최소 평형" />
      <input value={maxArea} onChange={(e) => setMaxArea(e.target.value)} placeholder="최대 평형(㎡)" aria-label="최대 평형" />

      <button type="submit">검색</button>
    </form>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- RegionSearch`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web/src/components/RegionSearch.jsx web/src/components/RegionSearch.test.jsx
git commit -m "feat: add RegionSearch component"
```

---

### Task 7: `TransactionList` component + `SearchPage`

**Files:**
- Create: `web/src/components/TransactionList.jsx`
- Test: `web/src/components/TransactionList.test.jsx`
- Create: `web/src/pages/SearchPage.jsx`

- [ ] **Step 1: Write the failing test for `TransactionList`**

`web/src/components/TransactionList.test.jsx`:
```jsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { TransactionList } from './TransactionList';

const sampleItems = [
  { id: 1, aptName: '테스트아파트', exclusiveArea: 84.9, dealAmount: 90000, dealYm: 202307 },
];

describe('TransactionList', () => {
  it('renders each transaction', () => {
    render(<TransactionList items={sampleItems} hasMore={false} loading={false} onLoadMore={vi.fn()} />);

    expect(screen.getByText(/테스트아파트/)).toBeInTheDocument();
  });

  it('shows a load-more button only when hasMore is true, and calls onLoadMore when clicked', async () => {
    const onLoadMore = vi.fn();
    const user = userEvent.setup();
    render(<TransactionList items={sampleItems} hasMore={true} loading={false} onLoadMore={onLoadMore} />);

    const button = screen.getByRole('button', { name: '더보기' });
    await user.click(button);

    expect(onLoadMore).toHaveBeenCalledTimes(1);
  });

  it('does not render a load-more button when hasMore is false', () => {
    render(<TransactionList items={sampleItems} hasMore={false} loading={false} onLoadMore={vi.fn()} />);

    expect(screen.queryByRole('button', { name: '더보기' })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- TransactionList`
Expected: FAIL — `TransactionList.jsx` doesn't exist yet

- [ ] **Step 3: Write the component**

`web/src/components/TransactionList.jsx`:
```jsx
export function TransactionList({ items, hasMore, loading, onLoadMore }) {
  return (
    <div>
      <ul>
        {items.map((tx) => (
          <li key={tx.id}>
            {tx.aptName} · {tx.exclusiveArea}㎡ · {tx.dealAmount.toLocaleString()}만원 · {tx.dealYm}
          </li>
        ))}
      </ul>
      {hasMore && (
        <button onClick={onLoadMore} disabled={loading}>더보기</button>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- TransactionList`
Expected: PASS

- [ ] **Step 5: Assemble `SearchPage`**

`web/src/pages/SearchPage.jsx`:
```jsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RegionSearch } from '../components/RegionSearch';
import { TransactionList } from '../components/TransactionList';
import { useTransactionSearch } from '../hooks/useTransactionSearch';

export function SearchPage() {
  const [condition, setCondition] = useState(null);
  const { items, nextCursor, loading, error, search, loadMore } = useTransactionSearch();

  function handleSearch(newCondition) {
    setCondition(newCondition);
    search(newCondition);
  }

  return (
    <div>
      <h1>실거래가 검색</h1>
      <RegionSearch onSearch={handleSearch} />
      {condition?.regionCode && (
        <p><Link to={`/regions/${condition.regionCode}`}>이 지역 시세추이 보기</Link></p>
      )}
      {error && <p role="alert">{error}</p>}
      <TransactionList
        items={items}
        hasMore={Boolean(nextCursor)}
        loading={loading}
        onLoadMore={() => loadMore(condition)}
      />
    </div>
  );
}
```

- [ ] **Step 6: Commit**

```bash
git add web/src/components/TransactionList.jsx web/src/components/TransactionList.test.jsx web/src/pages/SearchPage.jsx
git commit -m "feat: add TransactionList component and SearchPage"
```

---

### Task 8: `PriceTrendChart` component + `RegionDetailPage`

**Files:**
- Create: `web/src/components/PriceTrendChart.jsx`
- Test: `web/src/components/PriceTrendChart.test.jsx`
- Create: `web/src/pages/RegionDetailPage.jsx`

**Design:** chart rendering internals (canvas) aren't worth testing under jsdom — the test mocks `react-chartjs-2`'s `Line` component and only verifies the empty-state message and that the chart renders when data is present.

- [ ] **Step 1: Write the failing test**

`web/src/components/PriceTrendChart.test.jsx`:
```jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="mock-chart" />,
}));

import { PriceTrendChart } from './PriceTrendChart';

describe('PriceTrendChart', () => {
  it('shows a message when there is no data', () => {
    render(<PriceTrendChart monthlyStats={[]} />);

    expect(screen.getByText('표시할 데이터가 없습니다.')).toBeInTheDocument();
  });

  it('renders the chart when data is present', () => {
    render(<PriceTrendChart monthlyStats={[{ dealYm: 202301, count: 1, avgDealAmount: 90000 }]} />);

    expect(screen.getByTestId('mock-chart')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- PriceTrendChart`
Expected: FAIL — `PriceTrendChart.jsx` doesn't exist yet

- [ ] **Step 3: Write the component**

`web/src/components/PriceTrendChart.jsx`:
```jsx
import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
} from 'chart.js';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip);

export function PriceTrendChart({ monthlyStats }) {
  if (monthlyStats.length === 0) {
    return <p>표시할 데이터가 없습니다.</p>;
  }

  const data = {
    labels: monthlyStats.map((s) => String(s.dealYm)),
    datasets: [
      {
        label: '평균 거래가(만원)',
        data: monthlyStats.map((s) => s.avgDealAmount),
      },
    ],
  };

  return <Line data={data} />;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- PriceTrendChart`
Expected: PASS

- [ ] **Step 5: Assemble `RegionDetailPage`**

`web/src/pages/RegionDetailPage.jsx`:
```jsx
import { useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { PriceTrendChart } from '../components/PriceTrendChart';
import { useRegionStats } from '../hooks/useRegionStats';

export function RegionDetailPage() {
  const { regionCode } = useParams();
  const { monthlyStats, loading, error, loadRange } = useRegionStats();

  useEffect(() => {
    const to = currentYearMonth();
    const from = to - 100; // YYYYMM integer arithmetic: -100 steps back exactly one year
    loadRange(regionCode, from, to);
  }, [regionCode, loadRange]);

  return (
    <div>
      <p><Link to="/">← 검색으로</Link></p>
      <h1>{regionCode} 시세추이</h1>
      {loading && <p>로딩 중...</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && !error && <PriceTrendChart monthlyStats={monthlyStats} />}
    </div>
  );
}

function currentYearMonth() {
  const now = new Date();
  return now.getFullYear() * 100 + (now.getMonth() + 1);
}
```

- [ ] **Step 6: Commit**

```bash
git add web/src/components/PriceTrendChart.jsx web/src/components/PriceTrendChart.test.jsx web/src/pages/RegionDetailPage.jsx
git commit -m "feat: add PriceTrendChart component and RegionDetailPage"
```

---

### Task 9: Wire routing, manual verification, push + open PR

**Files:**
- Modify: `web/src/App.jsx`

- [ ] **Step 1: Wire the router**

`web/src/App.jsx`:
```jsx
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { SearchPage } from './pages/SearchPage';
import { RegionDetailPage } from './pages/RegionDetailPage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SearchPage />} />
        <Route path="/regions/:regionCode" element={<RegionDetailPage />} />
      </Routes>
    </BrowserRouter>
  );
}
```

- [ ] **Step 2: Run the full test suite and the build**

```bash
npm test
npm run build
```

Expected: all tests PASS, build succeeds.

- [ ] **Step 3: Commit**

```bash
git add web/src/App.jsx
git commit -m "feat: wire SearchPage and RegionDetailPage routes"
```

- [ ] **Step 4: Manual end-to-end verification**

1. From `.worktrees/api-module`: `docker compose up -d` (MariaDB + Redis), run the `ingest` job so `real_estate_transaction` has data for `11110`/`11140` at `202301`/`202302` (see `ingest` plan's manual verification section), then `./gradlew :api:bootRun`
2. From `.worktrees/web/web`: `npm run dev`, open the printed local URL
3. On the search page, select "서울특별시 종로구", set 기간 `202301`–`202302`, click 검색 — expect the seeded transactions to appear
4. Click "이 지역 시세추이 보기" — expect the region detail page to load and render a chart (or the "표시할 데이터가 없습니다" message if the auto-computed trailing-12-month range doesn't overlap the seeded `202301`/`202302` data — if so, this is expected given the tiny demo dataset, not a bug)
5. Click "← 검색으로" — expect navigation back to the search page

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin task/web
gh pr create --base task/api-module --head task/web --title "Task/web module" --body "$(cat <<'EOF'
## Summary
- React (Vite) SPA: search page (region/period/area filters, keyset-paginated list) + region detail page (price trend chart)
- Adds GET /api/regions/{regionCode}/stats/range to the api module (this PR's base branch) for the chart

## Test plan
- [x] api: ./gradlew :api:test
- [x] web: npm test
- [x] Manual verification per Task 9 Step 4 of docs/superpowers/plans/2026-07-22-web-module.md
EOF
)"
```

Expected: PR opened targeting `task/api-module` (mirrors how `task/api-module` itself targets `task/infra-and-ingest` — neither `ingest` nor `api` has merged to `main` yet).
