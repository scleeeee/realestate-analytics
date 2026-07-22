package com.realestate.ingest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaMigrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @Test
    void migratesAndCreatesPartitionedTable() throws Exception {
        Flyway.configure()
            .dataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection conn = DriverManager.getConnection(
                mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
                 "WHERE TABLE_NAME = 'real_estate_transaction' AND PARTITION_NAME IS NOT NULL " +
                 "ORDER BY PARTITION_ORDINAL_POSITION")) {
            List<String> partitions = new ArrayList<>();
            while (rs.next()) partitions.add(rs.getString(1));
            assertThat(partitions).containsExactly(
                "p2020", "p2021", "p2022", "p2023", "p2024", "p2025", "pmax");
        }
    }
}
