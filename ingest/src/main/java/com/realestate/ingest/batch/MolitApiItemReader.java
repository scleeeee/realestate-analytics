package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.MolitApiClient;
import org.springframework.batch.item.ItemReader;

import java.util.Iterator;
import java.util.List;

public class MolitApiItemReader implements ItemReader<AptTradeItem> {

    private static final int PAGE_SIZE = 1000;

    private final MolitApiClient client;
    private final String regionCode;
    private final String dealYm;

    private int pageNo = 1;
    private Iterator<AptTradeItem> currentPage = List.<AptTradeItem>of().iterator();
    private boolean exhausted = false;

    public MolitApiItemReader(MolitApiClient client, String regionCode, String dealYm) {
        this.client = client;
        this.regionCode = regionCode;
        this.dealYm = dealYm;
    }

    @Override
    public AptTradeItem read() {
        if (!currentPage.hasNext() && !exhausted) {
            List<AptTradeItem> page = client.fetchTrades(regionCode, dealYm, pageNo, PAGE_SIZE);
            if (page.isEmpty()) {
                exhausted = true;
            } else {
                currentPage = page.iterator();
                pageNo++;
            }
        }
        return currentPage.hasNext() ? currentPage.next() : null;
    }
}
