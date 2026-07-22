package com.realestate.ingest.client;

import java.util.List;
import java.util.Map;

public class FakeMolitApiClient implements MolitApiClient {

    private final Map<String, List<AptTradeItem>> pages;

    public FakeMolitApiClient(Map<String, List<AptTradeItem>> pages) {
        this.pages = pages;
    }

    @Override
    public List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows) {
        String key = regionCode + ":" + dealYm + ":" + pageNo;
        return pages.getOrDefault(key, List.of());
    }
}
