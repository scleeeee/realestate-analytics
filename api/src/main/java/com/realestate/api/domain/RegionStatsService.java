package com.realestate.api.domain;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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
}
