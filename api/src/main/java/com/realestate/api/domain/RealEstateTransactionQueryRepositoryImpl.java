package com.realestate.api.domain;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
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

    @Override
    public RegionStats statsFor(String regionCode, int dealYm) {
        var q = realEstateTransaction;
        Long count = queryFactory.select(q.count())
            .from(q)
            .where(q.regionCode.eq(regionCode), q.dealYm.eq(dealYm))
            .fetchOne();
        Double avg = queryFactory.select(q.dealAmount.avg())
            .from(q)
            .where(q.regionCode.eq(regionCode), q.dealYm.eq(dealYm))
            .fetchOne();

        long safeCount = count != null ? count : 0L;
        BigDecimal safeAvg = (avg != null) ? BigDecimal.valueOf(avg) : BigDecimal.ZERO;
        return new RegionStats(safeCount, safeAvg);
    }

    @Override
    public List<RegionMonthStats> statsForRange(String regionCode, int dealYmFrom, int dealYmTo) {
        var q = realEstateTransaction;
        List<Tuple> rows = queryFactory
            .select(q.dealYm, q.count(), q.dealAmount.avg())
            .from(q)
            .where(q.regionCode.eq(regionCode), q.dealYm.between(dealYmFrom, dealYmTo))
            .groupBy(q.dealYm)
            .orderBy(q.dealYm.asc())
            .fetch();

        return rows.stream()
            .map(row -> {
                Integer dealYm = row.get(q.dealYm);
                Long count = row.get(q.count());
                Double avg = row.get(q.dealAmount.avg());
                BigDecimal safeAvg = (avg != null) ? BigDecimal.valueOf(avg) : BigDecimal.ZERO;
                return new RegionMonthStats(dealYm, count != null ? count : 0L, safeAvg);
            })
            .toList();
    }
}
