package com.realestate.ingest.client;

import java.util.List;

public interface MolitApiClient {
    List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows);
}
