package org.trading.exchange.model;

public enum OrderState {
    NEW, PARTIALLY_FILLED, FILLED, CANCELLED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED;
    }
}
