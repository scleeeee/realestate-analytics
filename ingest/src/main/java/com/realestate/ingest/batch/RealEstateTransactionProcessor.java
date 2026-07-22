package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.springframework.batch.item.ItemProcessor;

public class RealEstateTransactionProcessor implements ItemProcessor<AptTradeItem, RealEstateTransaction> {

    @Override
    public RealEstateTransaction process(AptTradeItem item) {
        int dealYm = item.dealYear() * 100 + item.dealMonth();
        return new RealEstateTransaction(
            item.regionCode(),
            item.legalDong(),
            item.aptName(),
            item.exclusiveArea(),
            item.dealAmount(),
            item.dealYear(),
            item.dealMonth(),
            item.dealDay(),
            dealYm,
            item.floor(),
            item.buildYear()
        );
    }
}
