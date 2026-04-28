package org.trading.exchange.util;

import org.trading.exchange.model.Envelope;

public final class EnvelopeUtil {

    private EnvelopeUtil() {
    }

    public static <T> T unwrap(Envelope<T> envelope) {
        return envelope.payload();
    }

    public static <T> Envelope<T> wrap(long sequence, T payload) {
        return Envelope.of(sequence, payload);
    }
}
