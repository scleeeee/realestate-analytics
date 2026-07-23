package com.realestate.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties(IngestProperties.class)
public class IngestApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(IngestApplication.class, args);
        boolean runOnStartup = context.getEnvironment()
            .getProperty("ingest.run-on-startup", Boolean.class, false);
        if (runOnStartup) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
