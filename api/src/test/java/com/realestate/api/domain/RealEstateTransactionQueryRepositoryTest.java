package com.realestate.api.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RealEstateTransactionQueryRepositoryTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RealEstateTransactionRepository queryRepository;

    @BeforeEach
    void seed() {
        // 3 rows in region 11110, deal_ym 202305/202306/202307; 1 row in a different region
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES
                ('11110', '종로구', 'A', 84.9, 90000, 2023, 5, 1, 202305),
                ('11110', '종로구', 'B', 59.8, 70000, 2023, 6, 1, 202306),
                ('11110', '종로구', 'C', 59.8, 75000, 2023, 7, 1, 202307),
                ('11140', '중구', 'D', 59.8, 80000, 2023, 7, 1, 202307)
            """);
    }

    @Test
    void filtersByRegionAndReturnsNewestFirst() {
        var condition = new TransactionSearchCondition("11110", null, null, null, null);

        List<RealEstateTransaction> result = queryRepository.search(condition, null, 10);

        assertThat(result).extracting(RealEstateTransaction::getAptName)
            .containsExactly("C", "B", "A");
    }

    @Test
    void appliesKeysetCursorToSkipAlreadySeenRows() {
        var condition = new TransactionSearchCondition("11110", null, null, null, null);
        var cursorAtC = new TransactionCursor(202307, idOf("C"));

        List<RealEstateTransaction> result = queryRepository.search(condition, cursorAtC, 10);

        assertThat(result).extracting(RealEstateTransaction::getAptName)
            .containsExactly("B", "A");
    }

    @Test
    void aggregatesAveragePriceAndCountForRegionAndMonth() {
        RegionStats stats = queryRepository.statsFor("11110", 202307);

        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.avgDealAmount()).isEqualByComparingTo(java.math.BigDecimal.valueOf(75000));
    }

    @Test
    void returnsZeroCountWhenNoDataForRegionAndMonth() {
        RegionStats stats = queryRepository.statsFor("11110", 202312);

        assertThat(stats.count()).isZero();
    }

    private Long idOf(String aptName) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM real_estate_transaction WHERE apt_name = ?", Long.class, aptName);
    }
}
