package com.realestate.api.domain;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RegionStatsService {

    private final RealEstateTransactionRepository repository;

    public RegionStatsService(RealEstateTransactionRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "regionStats", key = "#regionCode + ':' + #dealYm")
    public RegionStats getStats(String regionCode, int dealYm) {
        return repository.statsFor(regionCode, dealYm);
    }

    @Cacheable(value = "regionStatsRange", key = "#regionCode + ':' + #dealYmFrom + ':' + #dealYmTo")
    public List<RegionMonthStats> getStatsRange(String regionCode, int dealYmFrom, int dealYmTo) {
        return repository.statsForRange(regionCode, dealYmFrom, dealYmTo);
    }
}
