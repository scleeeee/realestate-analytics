package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.domain.RealEstateTransaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealEstateTransactionProcessorTest {

    @Test
    void computesDealYmFromYearAndMonth() {
        var processor = new RealEstateTransactionProcessor();
        var item = new AptTradeItem("11110", "종로구", "테스트아파트", 84.95, 95000, 2023, 7, 15, 5, 2005);

        RealEstateTransaction result = processor.process(item);

        assertThat(result.dealYm()).isEqualTo(202307);
        assertThat(result.aptName()).isEqualTo("테스트아파트");
        assertThat(result.dealAmount()).isEqualTo(95000);
        assertThat(result.floor()).isEqualTo(5);
    }
}
