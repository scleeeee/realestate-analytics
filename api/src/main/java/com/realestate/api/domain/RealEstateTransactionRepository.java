package com.realestate.api.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RealEstateTransactionRepository
        extends JpaRepository<RealEstateTransaction, Long>, RealEstateTransactionQueryRepository {
}
