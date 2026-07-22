import { createPool } from './db.js';
import { runIndexScenario } from './scenarios/index-on-off.js';
import { runPartitionScenario } from './scenarios/partition-on-off.js';
import { runPaginationScenario } from './scenarios/offset-vs-keyset.js';
import { buildMarkdownReport, writeReport } from './report.js';

const DEEP_OFFSET = Number(process.argv[2] ?? 4_000_000);

async function main() {
  const pool = createPool();

  console.log('Running index on/off scenario...');
  const indexRows = await runIndexScenario(pool);

  console.log('Running partition on/off scenario...');
  const partitionRows = await runPartitionScenario(pool);

  console.log(`Running offset vs keyset scenario (deep offset: ${DEEP_OFFSET.toLocaleString()})...`);
  const paginationRows = await runPaginationScenario(pool, DEEP_OFFSET);

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
