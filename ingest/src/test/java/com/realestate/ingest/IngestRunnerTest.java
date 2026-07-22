package com.realestate.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void skipsAnAlreadyCompletedCombinationWithoutThrowing() throws Exception {
        var jobLauncher = mock(JobLauncher.class);
        when(jobLauncher.run(any(), any()))
            .thenThrow(new JobInstanceAlreadyCompleteException("already done"));
        var properties = new IngestProperties(List.of("11110"), List.of("202301"));
        var runner = new IngestRunner(jobLauncher, mock(Job.class), properties);

        assertThatCode(runner::run).doesNotThrowAnyException();
    }

    @Test
    void continuesToRemainingCombinationsAfterAnAlreadyCompletedOne() throws Exception {
        var jobLauncher = mock(JobLauncher.class);
        when(jobLauncher.run(any(), any()))
            .thenThrow(new JobInstanceAlreadyCompleteException("already done"))
            .thenReturn(mock(JobExecution.class));
        var properties = new IngestProperties(List.of("11110", "11140"), List.of("202301"));
        var runner = new IngestRunner(jobLauncher, mock(Job.class), properties);

        runner.run();

        verify(jobLauncher, times(2)).run(any(), any());
    }
}
