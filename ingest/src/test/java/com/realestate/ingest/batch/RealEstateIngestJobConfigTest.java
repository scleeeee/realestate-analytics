package com.realestate.ingest.batch;

import com.realestate.ingest.IngestApplication;
import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.FakeMolitApiClient;
import com.realestate.ingest.client.MolitApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest(classes = IngestApplication.class)
@Import(RealEstateIngestJobConfigTest.FakeClientConfig.class)
@Testcontainers
class RealEstateIngestJobConfigTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        public MolitApiClient molitApiClient() {
            var item1 = new AptTradeItem("11110", "종로구", "A아파트", 84.9, 90000, 2023, 7, 1, 3, 2000);
            var item2 = new AptTradeItem("11110", "종로구", "B아파트", 59.8, 70000, 2023, 7, 2, 10, 2010);
            return new FakeMolitApiClient(Map.of(
                "11110:202307:1", List.of(item1, item2)
            ));
        }
    }

    @Test
    void ingestsTwoTransactionsFromFakeApi() throws Exception {
        var jobParameters = new JobParametersBuilder()
            .addString("regionCode", "11110")
            .addString("dealYm", "202307")
            .addLong("runId", System.currentTimeMillis())
            .toJobParameters();

        var execution = jobLauncherTestUtils.launchJob(jobParameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM real_estate_transaction WHERE deal_ym = 202307", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
