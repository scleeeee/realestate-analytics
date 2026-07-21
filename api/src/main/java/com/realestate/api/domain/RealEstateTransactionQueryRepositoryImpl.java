package com.realestate.api.domain;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.util.List;

import static com.realestate.api.domain.QRealEstateTransaction.realEstateTransaction;

public class RealEstateTransactionQueryRepositoryImpl implements RealEstateTransactionQueryRepository {

    private final JPAQueryFactory queryFactory;

    public RealEstateTransactionQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<RealEstateTransaction> search(TransactionSearchCondition condition, TransactionCursor cursor, int size) {
        var q = realEstateTransaction;
        var predicate = new BooleanBuilder();

        if (condition.regionCode() != null) {
            predicate.and(q.regionCode.eq(condition.regionCode()));
        }
        if (condition.dealYmFrom() != null) {
            predicate.and(q.dealYm.goe(condition.dealYmFrom()));
        }
        if (condition.dealYmTo() != null) {
            predicate.and(q.dealYm.loe(condition.dealYmTo()));
        }
        if (condition.minArea() != null) {
            predicate.and(q.exclusiveArea.goe(condition.minArea()));
        }
        if (condition.maxArea() != null) {
            predicate.and(q.exclusiveArea.loe(condition.maxArea()));
        }
        if (cursor != null) {
            predicate.and(
                q.dealYm.lt(cursor.dealYm())
                    .or(q.dealYm.eq(cursor.dealYm()).and(q.id.lt(cursor.id())))
            );
        }

        return queryFactory.selectFrom(q)
            .where(predicate)
            .orderBy(q.dealYm.desc(), q.id.desc())
            .limit(size)
            .fetch();
    }
}
