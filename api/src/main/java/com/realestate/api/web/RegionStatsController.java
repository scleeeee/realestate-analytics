package com.realestate.api.web;

import com.realestate.api.domain.RegionStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RegionStatsController {

    private final RegionStatsService regionStatsService;

    public RegionStatsController(RegionStatsService regionStatsService) {
        this.regionStatsService = regionStatsService;
    }

    @GetMapping("/api/regions/{regionCode}/stats")
    public RegionStatsResponse stats(@PathVariable String regionCode, @RequestParam int dealYm) {
        return RegionStatsResponse.of(regionCode, dealYm, regionStatsService.getStats(regionCode, dealYm));
    }

    @GetMapping("/api/regions/{regionCode}/stats/range")
    public List<RegionStatsResponse> statsRange(
            @PathVariable String regionCode,
            @RequestParam int from,
            @RequestParam int to) {
        return regionStatsService.getStatsRange(regionCode, from, to).stream()
            .map(s -> new RegionStatsResponse(regionCode, s.dealYm(), s.count(), s.avgDealAmount()))
            .toList();
    }
}
