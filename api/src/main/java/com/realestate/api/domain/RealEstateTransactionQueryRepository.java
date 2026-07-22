package com.realestate.api.domain;

import java.util.List;

public interface RealEstateTransactionQueryRepository {
    List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size);

    RegionStats statsFor(String regionCode, int dealYm);

    List<RegionMonthStats> statsForRange(String regionCode, int dealYmFrom, int dealYmTo);

    List<RealEstateTransaction> searchByOffset(TransactionSearchCondition condition, int page, int size);
}
