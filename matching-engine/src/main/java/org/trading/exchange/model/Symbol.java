package org.trading.exchange.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public enum Symbol {
    TEST1, TEST2, AAPL, GOOGL, MSFT, AMZN;

    private static final Map<String, Symbol> BY_NAME =
                    Arrays.stream(values()).collect(Collectors.toMap(Enum::name, s -> s));

    public static Symbol from(String symbol) {
        Symbol match = tryFrom(symbol);
        if (match == null) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
        return match;
    }

    public static Symbol tryFrom(String symbol) {
        return symbol == null ? null : BY_NAME.get(symbol.toUpperCase());
    }
}
