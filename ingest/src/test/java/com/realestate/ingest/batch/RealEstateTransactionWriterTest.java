package com.realestate.ingest.batch;

import com.realestate.ingest.domain.RealEstateTransaction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RealEstateTransactionWriterTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    static DataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        var ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.mariadb.jdbc.Driver.class);
        ds.setUrl(mariadb.getJdbcUrl());
        ds.setUsername(mariadb.getUsername());
        ds.setPassword(mariadb.getPassword());
        dataSource = ds;

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @Test
    void writesChunkAndPersistsAllFields() {
        var writer = new RealEstateTransactionWriter(new JdbcTemplate(dataSource));
        var tx = new RealEstateTransaction(
            "11110", "종로구", "테스트아파트", 84.95, 95000,
            2023, 7, 15, 202307, 5, 2005);

        writer.write(new Chunk<>(List.of(tx)));

        var jdbcTemplate = new JdbcTemplate(dataSource);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM real_estate_transaction WHERE deal_ym = 202307", Integer.class);
        assertThat(count).isEqualTo(1);

        String aptName = jdbcTemplate.queryForObject(
            "SELECT apt_name FROM real_estate_transaction WHERE deal_ym = 202307", String.class);
        assertThat(aptName).isEqualTo("테스트아파트");
    }
}