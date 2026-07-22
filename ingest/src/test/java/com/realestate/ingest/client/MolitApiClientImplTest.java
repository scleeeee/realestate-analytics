package com.realestate.ingest.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MolitApiClientImplTest {

    private MockRestServiceServer server;
    private MolitApiClientImpl client;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MolitApiClientImpl(builder, "http://localhost:9999", "test-service-key");
    }

    @Test
    void parsesWhitespacePaddedNumericFieldsWithoutThrowing() {
        setUp();
        String xml = """
            <response>
              <body>
                <items>
                  <item>
                    <umdNm>종로구</umdNm>
                    <aptNm>테스트아파트</aptNm>
                    <excluUseAr>84.95</excluUseAr>
                    <dealAmount> 90,000 </dealAmount>
                    <dealYear> 2023 </dealYear>
                    <dealMonth> 7 </dealMonth>
                    <dealDay> 15 </dealDay>
                    <floor> 5 </floor>
                    <buildYear> 2005 </buildYear>
                  </item>
                </items>
              </body>
            </response>
            """;
        server.expect(requestTo(containsString("/getRTMSDataSvcAptTradeDev")))
            .andRespond(withSuccess(xml, MediaType.APPLICATION_XML));

        List<AptTradeItem> result = client.fetchTrades("11110", "202307", 1, 10);

        assertThat(result).hasSize(1);
        AptTradeItem item = result.get(0);
        assertThat(item.dealYear()).isEqualTo(2023);
        assertThat(item.dealMonth()).isEqualTo(7);
        assertThat(item.dealDay()).isEqualTo(15);
        assertThat(item.dealAmount()).isEqualTo(90000L);
        assertThat(item.floor()).isEqualTo(5);
        assertThat(item.buildYear()).isEqualTo(2005);
    }

    @Test
    void mapsAllFieldsForACleanResponse() {
        setUp();
        String xml = """
            <response>
              <body>
                <items>
                  <item>
                    <umdNm>종로구</umdNm>
                    <aptNm>테스트아파트</aptNm>
                    <excluUseAr>84.95</excluUseAr>
                    <dealAmount>90,000</dealAmount>
                    <dealYear>2023</dealYear>
                    <dealMonth>7</dealMonth>
                    <dealDay>15</dealDay>
                    <floor>5</floor>
                    <buildYear>2005</buildYear>
                  </item>
                </items>
              </body>
            </response>
            """;
        server.expect(requestTo(containsString("LAWD_CD=11110")))
            .andRespond(withSuccess(xml, new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8)));

        List<AptTradeItem> result = client.fetchTrades("11110", "202307", 1, 10);

        assertThat(result).containsExactly(new AptTradeItem(
            "11110", "종로구", "테스트아파트", 84.95, 90000L, 2023, 7, 15, 5, 2005));
    }

    @Test
    void returnsEmptyListWhenResponseHasNoItems() {
        setUp();
        String xml = """
            <response>
              <body>
              </body>
            </response>
            """;
        server.expect(requestTo(containsString("/getRTMSDataSvcAptTradeDev")))
            .andRespond(withSuccess(xml, MediaType.APPLICATION_XML));

        List<AptTradeItem> result = client.fetchTrades("11110", "202307", 1, 10);

        assertThat(result).isEmpty();
    }

    @Test
    void treatsMissingFloorAndBuildYearAsNull() {
        setUp();
        String xml = """
            <response>
              <body>
                <items>
                  <item>
                    <umdNm>종로구</umdNm>
                    <aptNm>테스트아파트</aptNm>
                    <excluUseAr>84.95</excluUseAr>
                    <dealAmount>90,000</dealAmount>
                    <dealYear>2023</dealYear>
                    <dealMonth>7</dealMonth>
                    <dealDay>15</dealDay>
                    <floor></floor>
                    <buildYear></buildYear>
                  </item>
                </items>
              </body>
            </response>
            """;
        server.expect(requestTo(containsString("/getRTMSDataSvcAptTradeDev")))
            .andRespond(withSuccess(xml, MediaType.APPLICATION_XML));

        List<AptTradeItem> result = client.fetchTrades("11110", "202307", 1, 10);

        assertThat(result.get(0).floor()).isNull();
        assertThat(result.get(0).buildYear()).isNull();
    }
}
