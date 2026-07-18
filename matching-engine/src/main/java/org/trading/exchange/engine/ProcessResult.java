package org.trading.exchange.engine;

public enum ProcessResult {

    ACCEPTED(null), DUPLICATE_CLIENT_ORDER_ID("Duplicate clientOrderId"), UNKNOWN_CLIENT_ORDER_ID(
                    "Unknown clientOrderId");

    private final String message;

    ProcessResult(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public boolean isRejected() {
        return this != ACCEPTED;
    }
}
