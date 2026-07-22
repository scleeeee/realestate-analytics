package com.realestate.api.web;

import java.util.List;

public record TransactionOffsetSearchResponse(List<TransactionResponse> items, int page, int size) {}
