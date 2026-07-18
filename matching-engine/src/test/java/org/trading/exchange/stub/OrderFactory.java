package org.trading.exchange.stub;

import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;

public final class OrderFactory {

    public static Order createLimitOrder(long id, String userId, OrderSide side, long price,
                    long quantity) {
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for limit orders");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, null, userId, null, side, OrderType.LIMIT, price, quantity,
                        System.currentTimeMillis());
    }

    public static Order createMarketOrder(long id, String userId, OrderSide side, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, null, userId, null, side, OrderType.MARKET, 0, quantity,
                        System.currentTimeMillis());
    }

    public static Order createIOCOrder(long id, String userId, OrderSide side, Long price,
                    Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for IOC orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, null, userId, null, side, OrderType.IOC, price, quantity,
                        System.currentTimeMillis());
    }

    public static Order createFOKOrder(long id, String userId, OrderSide side, Long price,
                    Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for FOK orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, null, userId, null, side, OrderType.FOK, price, quantity,
                        System.currentTimeMillis());
    }
}
