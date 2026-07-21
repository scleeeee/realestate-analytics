package com.realestate.ingest;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ingest.run-on-startup", havingValue = "true")
public class IngestRunner implements CommandLineRunner {

    private final JobLauncher jobLauncher;
    private final Job realEstateIngestJob;
    private final IngestProperties properties;

    public IngestRunner(JobLauncher jobLauncher, Job realEstateIngestJob, IngestProperties properties) {
        this.jobLauncher = jobLauncher;
        this.realEstateIngestJob = realEstateIngestJob;
        this.properties = properties;
    }

    @Override
    public void run(String... args) throws Exception {
        for (JobParameters params : buildAllJobParameters()) {
            jobLauncher.run(realEstateIngestJob, params);
        }
    }

    public List<JobParameters> buildAllJobParameters() {
        return properties.regionCodes().stream()
            .flatMap(region -> properties.dealYms().stream()
                .map(ym -> new JobParametersBuilder()
                    .addString("regionCode", region)
                    .addString("dealYm", ym)
                    .toJobParameters()))
            .toList();
    }
}
