package com.realestate.ingest.client;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.List;

@JsonRootName("response")
public record MolitApiResponse(Body body) {

    public static class Body {
        @JacksonXmlElementWrapper(localName = "items")
        @JacksonXmlProperty(localName = "item")
        private List<MolitApiItem> items;

        public List<MolitApiItem> items() {
            return items;
        }
    }

    public record MolitApiItem(
        String umdNm,
        String aptNm,
        String excluUseAr,
        String dealAmount,
        String dealYear,
        String dealMonth,
        String dealDay,
        String floor,
        String buildYear
    ) {}
}
