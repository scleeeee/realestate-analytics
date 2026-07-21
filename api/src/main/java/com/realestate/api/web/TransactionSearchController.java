package com.realestate.api.web;

import com.realestate.api.domain.RealEstateTransaction;
import com.realestate.api.domain.RealEstateTransactionRepository;
import com.realestate.api.domain.TransactionCursor;
import com.realestate.api.domain.TransactionSearchCondition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
public class TransactionSearchController {

    private final RealEstateTransactionRepository repository;

    public TransactionSearchController(RealEstateTransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/transactions")
    public TransactionSearchResponse search(
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Integer dealYmFrom,
            @RequestParam(required = false) Integer dealYmTo,
            @RequestParam(required = false) BigDecimal minArea,
            @RequestParam(required = false) BigDecimal maxArea,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        var condition = new TransactionSearchCondition(regionCode, dealYmFrom, dealYmTo, minArea, maxArea);
        var decodedCursor = cursor != null ? TransactionCursor.decode(cursor) : null;

        List<RealEstateTransaction> fetched = repository.search(condition, decodedCursor, size + 1);

        boolean hasNext = fetched.size() > size;
        List<RealEstateTransaction> page = hasNext ? fetched.subList(0, size) : fetched;

        String nextCursor = hasNext
            ? new TransactionCursor(page.get(page.size() - 1).getDealYm(), page.get(page.size() - 1).getId()).encode()
            : null;

        return new TransactionSearchResponse(page.stream().map(TransactionResponse::from).toList(), nextCursor);
    }
}
