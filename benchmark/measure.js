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
