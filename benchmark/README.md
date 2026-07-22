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
node run-benchmark.js                  # pass a number to override the default 4,000,000-row deep offset
```

Results are written to `benchmark/results/<date>-results.md`.
