package com.realestate.api.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class RegionStatsServiceTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegionStatsService regionStatsService;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'A', 84.9, 90000, 2023, 7, 1, 202307)
            """);
    }

    @Test
    void cachesStatsAcrossRepeatedCalls() {
        RegionStats first = regionStatsService.getStats("11110", 202307);
        // second insert should NOT be reflected if caching is working
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'B', 59.8, 70000, 2023, 7, 2, 202307)
            """);
        RegionStats second = regionStatsService.getStats("11110", 202307);

        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isEqualTo(1); // still 1 — cached, not re-queried
    }

    @Test
    void cachesRangeStatsAcrossRepeatedCalls() {
        // Uses deal_ym 202308 (not 202307, which seed() and cachesStatsAcrossRepeatedCalls
        // both write to) so this test stays self-contained regardless of method execution
        // order — RegionStatsServiceTest shares one Testcontainers DB across all @Test
        // methods with no cleanup between them.
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'X', 59.8, 60000, 2023, 8, 1, 202308)
            """);
        List<RegionMonthStats> first = regionStatsService.getStatsRange("11110", 202308, 202308);
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount, deal_year, deal_month, deal_day, deal_ym)
            VALUES ('11110', '종로구', 'Y', 59.8, 60000, 2023, 8, 2, 202308)
            """);
        List<RegionMonthStats> second = regionStatsService.getStatsRange("11110", 202308, 202308);

        assertThat(first.get(0).count()).isEqualTo(1);
        assertThat(second.get(0).count()).isEqualTo(1); // still 1 — cached, not re-queried
    }
}
