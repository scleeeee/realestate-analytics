package com.realestate.api.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionCursorTest {

    @Test
    void roundTripsThroughEncodeAndDecode() {
        var cursor = new TransactionCursor(202307, 42L);

        var decoded = TransactionCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void rejectsGarbageInput() {
        assertThatThrownBy(() -> TransactionCursor.decode("not-valid-base64!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
