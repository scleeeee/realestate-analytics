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
  if (!deepRow) {
    throw new Error(
      `No row at offset ${deepOffset} — the table has fewer rows than that. ` +
      'Pass a smaller deep-offset argument to run-benchmark.js.',
    );
  }
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
