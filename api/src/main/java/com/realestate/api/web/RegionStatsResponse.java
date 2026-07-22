package com.realestate.api.web;

import com.realestate.api.domain.RegionStats;

import java.math.BigDecimal;

public record RegionStatsResponse(String regionCode, int dealYm, long count, BigDecimal avgDealAmount) {
    public static RegionStatsResponse of(String regionCode, int dealYm, RegionStats stats) {
        return new RegionStatsResponse(regionCode, dealYm, stats.count(), stats.avgDealAmount());
    }
}
