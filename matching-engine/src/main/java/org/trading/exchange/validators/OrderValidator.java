package org.trading.exchange.validators;

import java.util.Objects;
import org.trading.exchange.model.Order;

public final class OrderValidator {

    public void validateInvariants(Order order) {
        String reason = invalidReason(order);
        if (reason != null) {
            throw new IllegalStateException(reason);
        }
    }

    public String invalidReason(Order order) {
        Objects.requireNonNull(order, "Order cannot be null");

        if (order.getOrderId() <= 0) {
            return "Order ID must be positive";
        }

        if (order.getRemainingQuantity() <= 0) {
            return "Quantity must be positive";
        }

        switch (order.getType()) {
            case LIMIT, IOC, FOK -> {
                if (order.getPrice() <= 0) {
                    return "Order type " + order.getType() + " requires positive price";
                }
            }
            case MARKET -> {
                if (order.getPrice() != 0) {
                    return "Market orders should not have price";
                }
            }
        }
        return null;
    }
}
