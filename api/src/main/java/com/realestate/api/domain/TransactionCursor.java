package com.realestate.api.domain;

import java.util.Base64;

public record TransactionCursor(int dealYm, long id) {

    public String encode() {
        String raw = dealYm + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    public static TransactionCursor decode(String encoded) {
        String raw = new String(Base64.getUrlDecoder().decode(encoded));
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor: " + encoded);
        }
        try {
            return new TransactionCursor(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor: " + encoded, e);
        }
    }
}
