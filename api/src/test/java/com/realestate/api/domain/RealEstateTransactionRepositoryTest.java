package com.realestate.api.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RealEstateTransactionRepositoryTest {

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
    private RealEstateTransactionRepository repository;

    @Test
    void findsPersistedTransactionById() {
        jdbcTemplate.update("""
            INSERT INTO real_estate_transaction
                (region_code, legal_dong, apt_name, exclusive_area, deal_amount,
                 deal_year, deal_month, deal_day, deal_ym, floor, build_year)
            VALUES ('11110', '종로구', '테스트아파트', 84.95, 95000, 2023, 7, 15, 202307, 5, 2005)
            """);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM real_estate_transaction", Long.class);

        RealEstateTransaction found = repository.findById(id).orElseThrow();

        assertThat(found.getAptName()).isEqualTo("테스트아파트");
        assertThat(found.getDealAmount()).isEqualTo(95000L);
        assertThat(found.getExclusiveArea()).isEqualByComparingTo(BigDecimal.valueOf(84.95));
        assertThat(found.getDealYm()).isEqualTo(202307);
    }
}
