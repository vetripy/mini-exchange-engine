package org.trading.exchange.model;


public enum Symbol {
    TEST1,
    TEST2;

    public static Symbol from(String symbol) {
        try {
            return Symbol.valueOf(symbol.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid symbol: " + symbol);
        }
    }
}
