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
