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
