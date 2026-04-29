package org.trading.exchange.validators;

import java.util.Objects;
import org.trading.exchange.model.Order;

public final class OrderValidator {

    public void validateInvariants(Order order) {
        Objects.requireNonNull(order, "Order cannot be null");
        Objects.requireNonNull(order.getOrderId(), "Order ID cannot be null");

        if (order.getRemainingQuantity() <= 0) {
            throw new IllegalStateException("Quantity must be positive");
        }

        switch (order.getType()) {
            case LIMIT, IOC, FOK -> {
                if (order.getPrice() == null || order.getPrice() <= 0) {
                    throw new IllegalStateException(
                                    "Order type " + order.getType() + " requires positive price");
                }
            }
            case MARKET -> {
                if (order.getPrice() != null) {
                    throw new IllegalStateException("Market orders should not have price");
                }
            }
        }
    }
}
