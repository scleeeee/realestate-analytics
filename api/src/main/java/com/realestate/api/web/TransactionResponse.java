package com.realestate.api.web;

import com.realestate.api.domain.RealEstateTransaction;

import java.math.BigDecimal;

public record TransactionResponse(
    Long id,
    String regionCode,
    String legalDong,
    String aptName,
    BigDecimal exclusiveArea,
    Long dealAmount,
    Integer dealYm,
    Integer floor,
    Integer buildYear
) {
    public static TransactionResponse from(RealEstateTransaction tx) {
        return new TransactionResponse(
            tx.getId(), tx.getRegionCode(), tx.getLegalDong(), tx.getAptName(),
            tx.getExclusiveArea(), tx.getDealAmount(), tx.getDealYm(), tx.getFloor(), tx.getBuildYear());
    }
}
