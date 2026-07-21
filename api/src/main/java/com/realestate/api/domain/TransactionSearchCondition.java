package com.realestate.api.domain;

import java.math.BigDecimal;

public record TransactionSearchCondition(
    String regionCode,
    Integer dealYmFrom,
    Integer dealYmTo,
    BigDecimal minArea,
    BigDecimal maxArea
) {}
