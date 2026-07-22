package com.realestate.ingest.client;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class MolitApiClientImpl implements MolitApiClient {

    private final RestClient restClient;
    private final XmlMapper xmlMapper = new XmlMapper();
    private final String serviceKey;

    public MolitApiClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${molit.base-url}") String baseUrl,
            @Value("${molit.service-key}") String serviceKey) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.serviceKey = serviceKey;
    }

    @Override
    public List<AptTradeItem> fetchTrades(String regionCode, String dealYm, int pageNo, int numOfRows) {
        String xml = restClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/getRTMSDataSvcAptTradeDev")
                .queryParam("serviceKey", serviceKey)
                .queryParam("LAWD_CD", regionCode)
                .queryParam("DEAL_YMD", dealYm)
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .build())
            .retrieve()
            .body(String.class);

        try {
            MolitApiResponse response = xmlMapper.readValue(xml, MolitApiResponse.class);
            if (response.body() == null || response.body().items() == null) {
                return List.of();
            }
            return response.body().items().stream()
                .map(item -> toAptTradeItem(item, regionCode))
                .toList();
        } catch (Exception e) {
            throw new IllegalStateException(
                "MOLIT API 응답 파싱 실패: regionCode=" + regionCode + ", dealYm=" + dealYm, e);
        }
    }

    private AptTradeItem toAptTradeItem(MolitApiResponse.MolitApiItem item, String regionCode) {
        // 거래금액은 "250,000" 형태(만원 단위)로 내려옴
        long dealAmount = Long.parseLong(item.dealAmount().replace(",", "").trim());
        return new AptTradeItem(
            regionCode,
            item.umdNm().trim(),
            item.aptNm().trim(),
            Double.parseDouble(item.excluUseAr().trim()),
            dealAmount,
            Integer.parseInt(item.dealYear().trim()),
            Integer.parseInt(item.dealMonth().trim()),
            Integer.parseInt(item.dealDay().trim()),
            item.floor() == null || item.floor().isBlank() ? null : Integer.parseInt(item.floor().trim()),
            item.buildYear() == null || item.buildYear().isBlank() ? null : Integer.parseInt(item.buildYear().trim())
        );
    }
}
