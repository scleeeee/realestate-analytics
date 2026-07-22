package com.realestate.api.domain;

import java.math.BigDecimal;

public record RegionMonthStats(int dealYm, long count, BigDecimal avgDealAmount) {}
