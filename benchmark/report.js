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
  mkdirSync('results', { recursive: true });
  const path = `results/${new Date().toISOString().slice(0, 10)}-results.md`;
  writeFileSync(path, markdown);
  return path;
}
