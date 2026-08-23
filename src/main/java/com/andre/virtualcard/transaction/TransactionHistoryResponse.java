package com.andre.virtualcard.transaction;

import org.springframework.data.domain.Slice;

import java.util.List;

public record TransactionHistoryResponse(
        List<CardTransactionResponse> items,
        int page,
        int size,
        boolean hasNext
) {

    public static TransactionHistoryResponse from(Slice<CardTransaction> slice, int page, int size) {
        return new TransactionHistoryResponse(
                slice.getContent().stream().map(CardTransactionResponse::from).toList(),
                page,
                size,
                slice.hasNext()
        );
    }
}
