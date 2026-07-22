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
