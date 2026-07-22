package com.realestate.ingest.batch;

import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.util.List;

public class RealEstateTransactionWriter implements ItemWriter<RealEstateTransaction> {

    private static final String INSERT_SQL = """
        INSERT INTO real_estate_transaction
            (region_code, legal_dong, apt_name, exclusive_area, deal_amount,
             deal_year, deal_month, deal_day, deal_ym, floor, build_year)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public RealEstateTransactionWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(Chunk<? extends RealEstateTransaction> chunk) {
        List<? extends RealEstateTransaction> items = chunk.getItems();
        jdbcTemplate.batchUpdate(INSERT_SQL, items, items.size(), (ps, tx) -> {
            ps.setString(1, tx.regionCode());
            ps.setString(2, tx.legalDong());
            ps.setString(3, tx.aptName());
            ps.setDouble(4, tx.exclusiveArea());
            ps.setLong(5, tx.dealAmount());
            ps.setInt(6, tx.dealYear());
            ps.setInt(7, tx.dealMonth());
            ps.setInt(8, tx.dealDay());
            ps.setInt(9, tx.dealYm());
            if (tx.floor() != null) {
                ps.setInt(10, tx.floor());
            } else {
                ps.setNull(10, Types.SMALLINT);
            }
            if (tx.buildYear() != null) {
                ps.setInt(11, tx.buildYear());
            } else {
                ps.setNull(11, Types.SMALLINT);
            }
        });
    }
}