package com.realestate.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ingest.run-on-startup", havingValue = "true")
public class IngestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

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
            try {
                jobLauncher.run(realEstateIngestJob, params);
            } catch (JobInstanceAlreadyCompleteException e) {
                // regionCode+dealYm are the only job parameters, so re-running the app
                // (e.g. a restart with ingest.run-on-startup still true) would otherwise
                // throw here for every combination already ingested successfully — skip
                // instead of letting it abort the remaining combinations.
                log.info("Skipping already-completed ingest job for parameters: {}", params);
            } catch (JobExecutionException e) {
                log.error("Failed to launch ingest job for parameters: {}", params, e);
            }
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
