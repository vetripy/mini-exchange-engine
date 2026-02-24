package org.trading.exchange.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@ToString
public class Order {
    private final String id;
    private final String userId;
    private final OrderSide side;
    private final Long price;
    private final Instant timestamp;
    private final OrderType type;
    private Long remainingQuantity;
    @Setter
    private OrderState state;

    private Order(String id, String userId, OrderSide side, OrderType type, Long price, Long quantity) {
        this.id = id;
        this.userId = userId;
        this.side = side;
        this.type = type;
        this.price = price;
        this.state = OrderState.NEW;
        this.remainingQuantity = quantity;
        this.timestamp = Instant.now();
    }

    public static Order createLimitOrder(String id, String userId, OrderSide side, Long price, Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for limit orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, userId, side, OrderType.LIMIT, price, quantity);
    }
    
    public static Order createMarketOrder(String id, String userId, OrderSide side, Long quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return new Order(id, userId, side, OrderType.MARKET, null, quantity);
    }

    public void reduceQuantity(long quantity) {
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Cannot reduce more than remaining quantity");
        }
        this.remainingQuantity -= quantity;
        if (this.remainingQuantity == 0) {
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIALLY_FILLED;
        }
    }
}
