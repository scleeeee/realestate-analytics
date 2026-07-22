package com.realestate.ingest.domain;

public record RealEstateTransaction(
    String regionCode,
    String legalDong,
    String aptName,
    double exclusiveArea,
    long dealAmount,
    int dealYear,
    int dealMonth,
    int dealDay,
    int dealYm,
    Integer floor,
    Integer buildYear
) {}
