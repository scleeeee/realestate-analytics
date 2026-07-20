package com.realestate.ingest.batch;

import com.realestate.ingest.client.AptTradeItem;
import com.realestate.ingest.client.FakeMolitApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MolitApiItemReaderTest {

    @Test
    void readsAcrossMultiplePagesThenReturnsNull() {
        var item1 = new AptTradeItem("11110", "종로구", "A아파트", 84.9, 90000, 2023, 7, 1, 3, 2000);
        var item2 = new AptTradeItem("11110", "종로구", "B아파트", 59.8, 70000, 2023, 7, 2, 10, 2010);

        var fakeClient = new FakeMolitApiClient(Map.of(
            "11110:202307:1", List.of(item1),
            "11110:202307:2", List.of(item2)
        ));

        var reader = new MolitApiItemReader(fakeClient, "11110", "202307");

        assertThat(reader.read()).isEqualTo(item1);
        assertThat(reader.read()).isEqualTo(item2);
        assertThat(reader.read()).isNull();
    }
}
