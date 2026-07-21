package com.realestate.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngestRunnerTest {

    @Test
    void buildsJobParametersForEveryRegionAndMonthCombination() {
        var properties = new IngestProperties(
            List.of("11110", "11140"),
            List.of("202301", "202302"));
        var runner = new IngestRunner(mock(JobLauncher.class), null, properties);

        List<JobParameters> result = runner.buildAllJobParameters();

        assertThat(result).hasSize(4);
        assertThat(result).extracting(p -> p.getString("regionCode"))
            .containsExactly("11110", "11110", "11140", "11140");
        assertThat(result).extracting(p -> p.getString("dealYm"))
            .containsExactly("202301", "202302", "202301", "202302");
    }
}
