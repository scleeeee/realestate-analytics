package com.realestate.api.web;

import java.util.List;

public record TransactionSearchResponse(List<TransactionResponse> items, String nextCursor) {}
