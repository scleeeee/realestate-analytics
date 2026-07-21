package com.realestate.api.domain;

import java.util.List;

public interface RealEstateTransactionQueryRepository {
    List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size);
}
