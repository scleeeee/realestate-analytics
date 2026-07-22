package com.realestate.ingest.client;

public record AptTradeItem(
    String regionCode,
    String legalDong,
    String aptName,
    double exclusiveArea,
    long dealAmount,
    int dealYear,
    int dealMonth,
    int dealDay,
    Integer floor,
    Integer buildYear
) {}
