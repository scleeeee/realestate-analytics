package com.realestate.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(List<String> regionCodes, List<String> dealYms) {}
