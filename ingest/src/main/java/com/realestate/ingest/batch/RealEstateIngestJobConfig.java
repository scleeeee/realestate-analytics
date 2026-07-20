package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.MolitApiClient;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class RealEstateIngestJobConfig {

    @Bean
    public Job realEstateIngestJob(JobRepository jobRepository, Step realEstateIngestStep) {
        return new JobBuilder("realEstateIngestJob", jobRepository)
            .start(realEstateIngestStep)
            .build();
    }

    @Bean
    public Step realEstateIngestStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<AptTradeItem> molitApiItemReader,
            ItemProcessor<AptTradeItem, RealEstateTransaction> realEstateTransactionProcessor,
            ItemWriter<RealEstateTransaction> realEstateTransactionWriter) {
        return new StepBuilder("realEstateIngestStep", jobRepository)
            .<AptTradeItem, RealEstateTransaction>chunk(1000, transactionManager)
            .reader(molitApiItemReader)
            .processor(realEstateTransactionProcessor)
            .writer(realEstateTransactionWriter)
            .build();
    }

    @Bean
    @StepScope
    public ItemReader<AptTradeItem> molitApiItemReader(
            MolitApiClient molitApiClient,
            @Value("#{jobParameters['regionCode']}") String regionCode,
            @Value("#{jobParameters['dealYm']}") String dealYm) {
        return new MolitApiItemReader(molitApiClient, regionCode, dealYm);
    }

    @Bean
    public ItemProcessor<AptTradeItem, RealEstateTransaction> realEstateTransactionProcessor() {
        return new RealEstateTransactionProcessor();
    }

    @Bean
    public ItemWriter<RealEstateTransaction> realEstateTransactionWriter(JdbcTemplate jdbcTemplate) {
        return new RealEstateTransactionWriter(jdbcTemplate);
    }
}
