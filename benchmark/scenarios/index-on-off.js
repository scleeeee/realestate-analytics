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
